(ns metabase.query-processor.middleware.annotate
  "Middleware for annotating (adding type information to) the results of a query, under the `:cols` column."
  (:require [clojure.string :as str]
            [medley.core :as m]
            [metabase
             [driver :as driver]
             [util :as u]]
            [metabase.driver.common :as driver.common]
            [metabase.mbql
             [predicates :as mbql.preds]
             [schema :as mbql.s]
             [util :as mbql.u]]
            [metabase.models.humanization :as humanization]
            [metabase.query-processor.store :as qp.store]
            [metabase.util
             [i18n :refer [tru]]
             [schema :as su]]
            [schema.core :as s]))

(def ^:private Col
  "Schema for a valid map of column info as found in the `:cols` key of the results after this namespace has ran."
  ;; name and display name can be blank because some wacko DBMSes like SQL Server return blank column names for
  ;; unaliased aggregations like COUNT(*) (this only applies to native queries, since we determine our own names for
  ;; MBQL.)
  {:name                          s/Str
   :display_name                  s/Str
   ;; type of the Field. For Native queries we look at the values in the first 100 rows to make an educated guess
   :base_type                     su/FieldType
   (s/optional-key :special_type) (s/maybe su/FieldType)
   ;; where this column came from in the original query.
   :source                        (s/enum :aggregation :fields :breakout :native)
   ;; a field clause that can be used to refer to this Field if this query is subsequently used as a source query.
   ;; Added by this middleware as one of the last steps.
   (s/optional-key :field_ref)    mbql.s/FieldOrAggregationReference
   ;; various other stuff from the original Field can and should be included such as `:settings`
   s/Any                          s/Any})

(defmulti column-info
  "Determine the `:cols` info that should be returned in the query results, which is a sequence of maps containing
  information about the columns in the results. Dispatches on query type."
  {:arglists '([query results])}
  (fn [query _]
    (:type query)))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                      Adding :cols info for native queries                                      |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- check-driver-native-columns
  "Double-check that the *driver* returned the correct number of `columns` for native query results."
  [columns rows]
  (when (seq rows)
    (let [expected-count (count columns)
          actual-count   (count (first rows))]
      (when-not (= expected-count actual-count)
        (throw (ex-info (str (tru "Query processor error: number of columns returned by driver does not match results.")
                             "\n"
                             (tru "Expected {0} columns, but first row of resuls has {1} columns."
                                  expected-count actual-count))
                 {:expected-columns columns
                  :first-row        (first rows)}))))))

(defmethod column-info :native
  [_ {:keys [columns rows]}]
  (check-driver-native-columns columns rows)
  ;; Infer the types of columns by looking at the first value for each in the results. Native queries don't have the
  ;; type information from the original `Field` objects used in the query.
  (vec
   (for [i    (range (count columns))
         :let [col       (nth columns i)
               base-type (or (driver.common/values->base-type (for [row rows]
                                                                (nth row i)))
                             :type/*)]]
     (merge
      {:name         (name col)
       :display_name (u/qualified-name col)
       :base_type    base-type
       :source       :native}
      ;; It is perfectly legal for a driver to return a column with a blank name; for example, SQL Server does this
      ;; for aggregations like `count(*)` if no alias is used. However, it is *not* legal to use blank names in MBQL
      ;; `:field-literal` clauses, because `SELECT ""` doesn't make any sense. So if we can't return a valid
      ;; `:field-literal`, omit the `:field_ref`.
      (when (seq (name col))
        {:field_ref [:field-literal (name col) base-type]})))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                       Adding :cols info for MBQL queries                                       |
;;; +----------------------------------------------------------------------------------------------------------------+

(s/defn ^:private join-with-alias :- (s/maybe mbql.s/Join)
  [{:keys [joins]} :- su/Map, join-alias :- su/NonBlankString]
  (some
   (fn [{:keys [alias], :as join}]
     (when (= alias join-alias)
       join))
   joins))

;;; --------------------------------------------------- Field Info ---------------------------------------------------

(s/defn ^:private display-name-for-joined-field
  "Return an appropriate display name for a joined field that includes the table it came from if applicable."
  [field-display-name {:keys [source-table], join-alias :alias}]
  (let [join-display-name (if (integer? source-table)
                            (some (qp.store/table source-table) [:display_name :name])
                            join-alias)]
    (format "%s → %s" join-display-name field-display-name)))

(defn- infer-expression-type
  [expression]
  (if (mbql.u/datetime-arithmetics? expression)
    {:base_type    :type/DateTime
     :special_type nil}
    {:base_type    :type/Float
     :special_type :type/Number}))

(s/defn ^:private col-info-for-field-clause :- {:field_ref mbql.s/Field, s/Keyword s/Any}
  [{:keys [source-metadata expressions], :as inner-query} :- su/Map, clause :- mbql.s/Field]
  ;; for various things that can wrap Field clauses recurse on the wrapped Field but include a little bit of info
  ;; about the clause doing the wrapping
  (mbql.u/match-one clause
    [:binning-strategy field strategy _ resolved-options]
    (let [recursive-info (col-info-for-field-clause inner-query field)]
      (assoc recursive-info
        :binning_info (assoc (u/snake-keys resolved-options)
                        :binning_strategy strategy)
        :field_ref    (assoc (vec &match) 1 (:field_ref recursive-info))))

    [:datetime-field field unit]
    (let [recursive-info (col-info-for-field-clause inner-query field)]
      (assoc recursive-info
        :unit      unit
        :field_ref (assoc (vec &match) 1 (:field_ref recursive-info))))

    [:joined-field join-alias field]
    (let [{:keys [fk-field-id], :as join} (join-with-alias inner-query join-alias)]
      (let [recursive-info (col-info-for-field-clause inner-query field)]
        (-> recursive-info
            (merge (when fk-field-id {:fk_field_id fk-field-id}))
            (assoc :field_ref (if fk-field-id
                                [:fk-> [:field-id fk-field-id] field]
                                (assoc (vec &match) 2 (:field_ref recursive-info))))
            (update :display_name display-name-for-joined-field join))))

    ;; TODO - should be able to remove this now
    [:fk-> [:field-id source-field-id] field]
    (assoc (col-info-for-field-clause inner-query field)
      :field_ref  &match
      :fk_field_id source-field-id)

    ;; TODO - should be able to remove this now
    ;; for FKs where source is a :field-literal don't include `:fk_field_id`
    [:fk-> _ field]
    (assoc (col-info-for-field-clause inner-query field)
      :field_ref &match)

    ;; for field literals, look for matching `source-metadata`, and use that if we can find it; otherwise generate
    ;; basic info based on the content of the field literal
    [:field-literal field-name field-type]
    (assoc (or (some #(when (= (:name %) field-name) %) source-metadata)
               {:name         field-name
                :base_type    field-type
                :display_name (humanization/name->human-readable-name field-name)})
      :field_ref &match)

    [:expression expression-name]
    (if-let [matching-expression (when (seq expressions)
                                   (some expressions ((juxt keyword u/qualified-name) expression-name)))]
      (merge
       ;; There's some inconsistency when expression names are keywords and when strings.
       ;; TODO: remove this duality once https://github.com/metabase/mbql/issues/5 is resolved.
       (infer-expression-type matching-expression)
       {:name            expression-name
        :display_name    expression-name
        ;; provided so the FE can add easily add sorts and the like when someone clicks a column header
        :expression_name expression-name
        :field_ref       &match})
      (throw (ex-info (str (tru "No expression named {0} found. Found: {1}" expression-name (keys expressions)))
               {:type :invalid-query, :clause &match, :expressions expressions})))

    [:field-id id]
    (let [{parent-id :parent_id, :as field} (dissoc (qp.store/field id) :database_type)]
      (assoc (if-not parent-id
               field
               (let [parent (col-info-for-field-clause inner-query [:field-id parent-id])]
                 (update field :name #(str (:name parent) \. %))))
        :field_ref &match))

    ;; we should never reach this if our patterns are written right so this is more to catch code mistakes than
    ;; something the user should expect to see
    _ (throw (ex-info (str (tru "Don't know how to get information about Field:") " " &match)
               {:field &match}))))


;;; ---------------------------------------------- Aggregate Field Info ----------------------------------------------

(defn- expression-arg-display-name
  "Generate an appropriate name for an `arg` in an expression aggregation."
  [recursive-name-fn arg]
  (mbql.u/match-one arg
    ;; if the arg itself is a nested expression, recursively find a name for it, and wrap in parens
    [(_ :guard #{:+ :- :/ :*}) & _]
    (str "(" (recursive-name-fn &match) ")")

    ;; if the arg is another aggregation, recurse to get its name. (Only aggregations, nested expressions, or numbers
    ;; are allowed as args to expression aggregations; thus anything that's an MBQL clause, but not a nested
    ;; expression, is a ag clause.)
    [(_ :guard keyword?) & _]
    (recursive-name-fn &match)

    ;; otherwise for things like numbers just use that directly
    _ &match))

(s/defn aggregation-name :- su/NonBlankString
  "Return an appropriate aggregation name/alias *used inside a query* for an `:aggregation` subclause (an aggregation
  or expression). Takes an options map as schema won't support passing keypairs directly as a varargs.

  These names are also used directly in queries, e.g. in the equivalent of a SQL `AS` clause."
  [ag-clause :- mbql.s/Aggregation & [{:keys [recursive-name-fn], :or {recursive-name-fn aggregation-name}}]]
  (when-not driver/*driver*
    (throw (Exception. (str (tru "*driver* is unbound.")))))
  (mbql.u/match-one ag-clause
    [:aggregation-options _ (options :guard :name)]
    (:name options)

    [:aggregation-options ag _]
    (recur ag)

    ;; For unnamed expressions, just compute a name like "sum + count"
    ;; Top level expressions need a name without a leading __ as those are automatically removed from the results
    [(operator :guard #{:+ :- :/ :*}) & args]
    "expression"

    ;; for historic reasons a distinct count clause is still named "count". Don't change this or you might break FE
    ;; stuff that keys off of aggregation names.
    ;;
    ;; `cum-count` and `cum-sum` get the same names as the non-cumulative versions, due to limitations (they are
    ;; written out of the query before we ever see them). For other code that makes use of this function give them
    ;; the correct names to expect.
    [(_ :guard #{:distinct :cum-count}) _]
    "count"

    [:sum-sum _]
    "sum"

    ;; for any other aggregation just use the name of the clause e.g. `sum`.
    [clause-name & _]
    (name clause-name)))

(declare aggregation-display-name)

(s/defn ^:private aggregation-arg-display-name :- su/NonBlankString
  "Name to use for an aggregation clause argument such as a Field when constructing the complete aggregation name."
  [inner-query, ag-arg :- Object]
  (or (when (mbql.preds/Field? ag-arg)
        (when-let [info (col-info-for-field-clause inner-query ag-arg)]
          (some info [:display_name :name])))
      (aggregation-display-name inner-query ag-arg)))

(s/defn aggregation-display-name :- su/NonBlankString
  "Return an appropriate user-facing display name for an aggregation clause."
  [inner-query ag-clause]
  (mbql.u/match-one ag-clause
    [:aggregation-options _ (options :guard :display-name)]
    (:display-name options)

    [:aggregation-options ag _]
    (recur ag)

    [(operator :guard #{:+ :- :/ :*}) & args]
    (str/join (format " %s " (name operator))
              (for [arg args]
                (expression-arg-display-name (partial aggregation-arg-display-name inner-query) arg)))

    [:count]             (str (tru "Count"))
    [:distinct    arg]   (str (tru "Distinct values of {0}"  (aggregation-arg-display-name inner-query arg)))
    [:count       arg]   (str (tru "Count of {0}"            (aggregation-arg-display-name inner-query arg)))
    [:avg         arg]   (str (tru "Average of {0}"          (aggregation-arg-display-name inner-query arg)))
    ;; cum-count and cum-sum get names for count and sum, respectively (see explanation in `aggregation-name`)
    [:cum-count   arg]   (str (tru "Count of {0}"            (aggregation-arg-display-name inner-query arg)))
    [:cum-sum     arg]   (str (tru "Sum of {0}"              (aggregation-arg-display-name inner-query arg)))
    [:stddev      arg]   (str (tru "SD of {0}"               (aggregation-arg-display-name inner-query arg)))
    [:sum         arg]   (str (tru "Sum of {0}"              (aggregation-arg-display-name inner-query arg)))
    [:min         arg]   (str (tru "Min of {0}"              (aggregation-arg-display-name inner-query arg)))
    [:max         arg]   (str (tru "Max of {0}"              (aggregation-arg-display-name inner-query arg)))

    ;; until we have a way to generate good names for filters we'll just have to say 'matching condition' for now
    [:sum-where   arg _] (str (tru "Sum of {0} matching condition" (aggregation-arg-display-name inner-query arg)))
    [:share       _]     (str (tru "Share of rows matching condition"))
    [:count-where _]     (str (tru "Count of rows matching condition"))

    (_ :guard mbql.preds/Field?)
    (:display_name (col-info-for-field-clause inner-query ag-clause))

    _
    (aggregation-name ag-clause {:recursive-name-fn (partial aggregation-arg-display-name inner-query)})))

(defn- ag->name-info [inner-query ag]
  {:name         (aggregation-name ag)
   :display_name (aggregation-display-name inner-query ag)})

(s/defn col-info-for-aggregation-clause
  "Return appropriate column metadata for an `:aggregation` clause."
  ; `clause` is normally an aggregation clause but this function can call itself recursively; see comments by the
  ; `match` pattern for field clauses below
  [inner-query :- su/Map, clause]
  (mbql.u/match-one clause
    ;; ok, if this is a aggregation w/ options recurse so we can get information about the ag it wraps
    [:aggregation-options ag _]
    (merge
     (col-info-for-aggregation-clause inner-query ag)
     (ag->name-info inner-query &match))

    ;; Always treat count or distinct count as an integer even if the DB in question returns it as something
    ;; wacky like a BigDecimal or Float
    [(_ :guard #{:count :distinct}) & args]
    (merge
     (col-info-for-aggregation-clause inner-query args)
     {:base_type    :type/Integer
      :special_type :type/Number}
     (ag->name-info inner-query &match))

    [:count-where _]
    (merge
     {:base_type    :type/Integer
      :special_type :type/Number}
     (ag->name-info inner-query &match))

    [:share _]
    (merge
     {:base_type    :type/Float
      :special_type :type/Number}
     (ag->name-info inner-query &match))

    ;; get info from a Field if we can (theses Fields are matched when ag clauses recursively call
    ;; `col-info-for-ag-clause`, and this info is added into the results)
    (_ :guard mbql.preds/Field?)
    (select-keys (col-info-for-field-clause inner-query &match) [:base_type :special_type :settings])

    ;; For the time being every Expression is an arithmetic operator and returns a floating-point number, so
    ;; hardcoding these types is fine; In the future when we extend Expressions to handle more functionality
    ;; we'll want to introduce logic that associates a return type with a given expression. But this will work
    ;; for the purposes of a patch release.
    #{:expression :+ :- :/ :*}
    (merge
     (infer-expression-type &match)
     (when (mbql.preds/Aggregation? &match)
       (ag->name-info inner-query &match)))

    ;; get name/display-name of this ag
    [(_ :guard keyword?) arg & _]
    (merge
     (col-info-for-aggregation-clause inner-query arg)
     (ag->name-info inner-query &match))))


;;; ----------------------------------------- Putting it all together (MBQL) -----------------------------------------

(defn- check-correct-number-of-columns-returned [returned-mbql-columns results]
  (let [expected-count (count returned-mbql-columns)
        actual-count   (count (:columns results))]
    (when (seq (:rows results))
      (when-not (= expected-count actual-count)
        (throw
         (Exception.
          (str (tru "Query processor error: mismatched number of columns in query and results.")
               " "
               (tru "Expected {0} fields, got {1}" expected-count actual-count)
               "\n"
               (tru "Expected: {0}" (mapv :name returned-mbql-columns))
               "\n"
               (tru "Actual: {0}" (vec (:columns results))))))))))

(s/defn ^:private cols-for-fields
  [{:keys [fields], :as inner-query} :- su/Map]
  (for [field fields]
    (assoc (col-info-for-field-clause inner-query field)
      :source :fields)))

(s/defn ^:private cols-for-ags-and-breakouts
  [{aggregations :aggregation, breakouts :breakout, :as inner-query} :- su/Map]
  (concat
   (for [breakout breakouts]
     (assoc (col-info-for-field-clause inner-query breakout)
       :source :breakout))
   (for [[i aggregation] (m/indexed aggregations)]
     (assoc (col-info-for-aggregation-clause inner-query aggregation)
       :source    :aggregation
       :field_ref [:aggregation i]))))

(s/defn cols-for-mbql-query
  "Return results metadata about the expected columns in an 'inner' MBQL query."
  [inner-query :- su/Map]
  (concat
   (cols-for-ags-and-breakouts inner-query)
   (cols-for-fields inner-query)))

(declare mbql-cols)

(defn- maybe-merge-source-metadata
  "Merge information from `source-metadata` into the returned `cols` for queries that return the columns of a source
  query as-is (i.e., the parent query does not have breakouts, aggregations, or an explicit`:fields` clause --
  excluding the one added automatically by `add-source-metadata`)."
  [source-metadata cols]
  (if (= (count cols) (count source-metadata))
    (map merge source-metadata cols)
    cols))

(defn- cols-for-source-query
  [{:keys [source-metadata], {native-source-query :native, :as source-query} :source-query} results]
  (if native-source-query
    (maybe-merge-source-metadata source-metadata (column-info {:type :native} results))
    (mbql-cols source-query results)))

(s/defn ^:private mbql-cols
  "Return the `:cols` result metadata for an 'inner' MBQL query based on the fields/breakouts/aggregations in the query."
  [{:keys [source-metadata source-query fields], :as inner-query} :- su/Map, results]
  (let [cols (cols-for-mbql-query inner-query)]
    (cond
      (and (empty? cols) source-query)
      (cols-for-source-query inner-query results)

      (every? (partial mbql.u/is-clause? :field-literal) fields)
      (maybe-merge-source-metadata source-metadata cols)

      :else
      cols)))

(defmethod column-info :query
  [{inner-query :query, :as query} results]
  (u/prog1 (mbql-cols inner-query results)
    (check-correct-number-of-columns-returned <> results)))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Deduplicating names                                               |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private ColsWithUniqueNames
  (s/constrained [Col] #(su/empty-or-distinct? (map :name %)) ":cols with unique names"))

(s/defn ^:private deduplicate-cols-names :- ColsWithUniqueNames
  [cols :- [Col]]
  (map (fn [col unique-name]
         (assoc col :name unique-name))
       cols
       (mbql.u/uniquify-names (map :name cols))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                           add-column-info middleware                                           |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- merge-cols-returned-by-driver
  "If the driver returned a `:cols` map with its results, which is completely optional, merge our `:cols` derived
  from logic above with theirs. We'll prefer the values in theirs to ours. This is important for wacky drivers
  like GA that use things like native metrics, which we have no information about.

  It's the responsibility of the driver to make sure the `:cols` are returned in the correct number and order."
  [cols cols-returned-by-driver]
  (if (seq cols-returned-by-driver)
    (map merge cols cols-returned-by-driver)
    cols))

(s/defn ^:private add-column-info* :- {:cols ColsWithUniqueNames, s/Keyword s/Any}
  [query {cols-returned-by-driver :cols, :as results}]
  ;; merge in `:cols` if returned by the driver, then make sure the `:name` of each map in `:cols` is unique, since
  ;; the FE uses it as a key for stuff like column settings
  (let [cols (deduplicate-cols-names
              (merge-cols-returned-by-driver (column-info query results) cols-returned-by-driver))]
    (-> results
        (assoc :cols cols)
        ;; remove `:columns` which we no longer need
        (dissoc :columns))))

(defn add-column-info
  "Middleware for adding type information about the columns in the query results (the `:cols` key)."
  [qp]
  (fn [query]
    (add-column-info* query (qp query))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                     result-rows-maps-to-vectors middleware                                     |
;;; +----------------------------------------------------------------------------------------------------------------+

(s/defn ^:private expected-column-sort-order :- [s/Keyword]
  "Determine query result row column names (as keywords) sorted in the appropriate order based on a `query`."
  [{query-type :type, inner-query :query, :as query} :- su/Map, {[first-row] :rows, :as results}]
  (if (= query-type :query)
    (map (comp keyword :name) (mbql-cols inner-query results))
    (map keyword (keys first-row))))

(defn- result-rows-maps->vectors* [query {[first-row :as rows] :rows, columns :columns, :as results}]
  (when (or (map? first-row)
            ;; if no rows were returned and the driver didn't return `:columns`, go ahead and calculate them so we can
            ;; add them -- drivers that rely on this behavior still need us to do that for them for queries that
            ;; return no results
            (and (empty? rows)
                 (nil? columns)))
    (let [sorted-columns (expected-column-sort-order query results)]
      (assoc results
        :columns (map u/qualified-name sorted-columns)
        :rows    (for [row rows]
                   (for [col sorted-columns]
                     (get row col)))))))

(defn result-rows-maps->vectors
  "For drivers that return query result rows as a sequence of maps rather than a sequence of vectors, determine
  appropriate column sort order and convert rows to sequences (the expected MBQL result format).

  Certain databases like MongoDB and Druid always return result rows as maps, rather than something sequential (e.g.
  vectors). Rather than require those drivers to duplicate the logic in this and other QP middleware namespaces for
  determining expected column sort order, drivers have the option of leaving the `:rows` as a sequence of maps, and
  this middleware will handle things for them.

  Because the order of `:columns` is determined by this middleware, it adds `:columns` to the results as well as the
  updated `:rows`; drivers relying on this middleware should return a map containing only `:rows`.

  IMPORTANT NOTES:

  *  Determining correct sort order only works for MBQL queries. For native queries, the sort order is the result of
     calling `keys` on the first row. It is reccomended that you utilize Flatland `ordered-map` when possible to
     preserve key ordering in maps.

  *  For obvious reasons, drivers that returns rows as maps cannot support duplicate column names. Thus it is expected
     that drivers that use functionality provided by this middleware return deduplicated column names, e.g. `:sum` and
     `:sum_2` for queries with multiple `:sum` aggregations.

  *  For *nested* Fields, this namespace assumes result row maps will come back flattened, and Field name keys will
     come back qualified by names of their ancestors, e.g. `parent.child`, `grandparent.parent.child`, etc. This is done
     to remove any ambiguity between nested columns with the same name (e.g. a document with both `user.id` and
     `venue.id`). Be sure to follow this convention if your driver supports nested Fields (e.g., MongoDB)."
  [qp]
  (fn [query]
    (let [results (qp query)]
      (or
       (result-rows-maps->vectors* query results)
       results))))
