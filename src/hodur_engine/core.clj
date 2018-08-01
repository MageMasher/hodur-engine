(ns hodur-engine.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [datascript.core :as d]))

(def ^:private temp-id-counter (atom 0))

(def ^:private temp-id-map (atom {}))

(def ^:private meta-schema
  {:type/name {:db/unique :db.unique/identity}
   :type/implements  {:db/cardinality :db.cardinality/many
                      :db/valueType   :db.type/ref}
   :type/interface   {:db/index true}

   :field/name       {:db/index true}
   :field/parent     {:db/cardinality :db.cardinality/one
                      :db/valueType   :db.type/ref}
   :field/type       {:db/cardinality :db.cardinality/one
                      :db/valueType   :db.type/ref} 

   :param/name       {:db/index true}
   :param/parent     {:db/cardinality :db.cardinality/one
                      :db/valueType   :db.type/ref}
   :param/type       {:db/cardinality :db.cardinality/one
                      :db/valueType   :db.type/ref}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FIXME: move these to a README/TUTORIAL when one is available
;; Some queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def all-interfaces
  '[:find [(pull ?t [* {:type/_implements [*]}]) ...]
    :where
    [?t :type/interface true]])

(def all-types
  '[:find [(pull ?t [* {:type/implements [*]
                        :field/_parent
                        [* {:field/type [*]
                            :param/_parent
                            [* {:param/type [*]}]}]}]) ...]
    :where
    [?t :type/name]])

(def one-type
  '[:find [(pull ?t [* {:type/implements [*]
                        :field/_parent
                        [* {:field/type [*]
                            :param/_parent
                            [* {:param/type [*]}]}]}]) ...]
    :in $ ?n
    :where
    [?t :type/name ?n]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Internal utils
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private schema-files
  [in]
  (->> in
       io/file
       file-seq
       (filter #(string/ends-with?
                 (.getPath ^java.io.File %)
                 ".edn"))))

(defn ^:private conj-vals
  [a coll]
  (reduce (fn [accum i]
            (conj accum i))
          a coll))

(defn ^:private reduce-all-files
  [files]
  (reduce (fn [a file]
            (conj-vals a (-> file slurp edn/read-string)))
          [] files))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Temp ID state stuff
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private reset-temp-id-state!
  []
  (reset! temp-id-counter 0)
  (reset! temp-id-map {}))

(defn ^:private next-temp-id!
  []
  (swap! temp-id-counter dec))

(defn ^:private set-temp-id!
  [i]
  (swap! temp-id-map assoc i (next-temp-id!)))

(defn ^:private get-temp-id!
  ([t i]
   (get-temp-id! (str t "-" i)))
  ([i]
   (if-let [out (get @temp-id-map i)]
     out
     (get (set-temp-id! i) i))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parsing functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private implements-reader
  [k & coll]
  {:new-v (map (fn [sym] {:db/id (get-temp-id! sym)})
               (flatten coll))})

(defn ^:private create-type-reader
  [ns]
  (fn [k sym]
    {:new-k (keyword ns "type")
     :new-v {:db/id (get-temp-id! sym)}}))

(defn ^:private expanded-key
  [ns k]
  (if (namespace k)
    k
    (keyword ns (name k))))

(defn ^:private find-and-run-reader
  [reader-map ns k v]
  (let [expanded-k (expanded-key ns k)
        out {:new-k expanded-k
             :new-v v}]
    (if-let [reader-fn (get reader-map k)]
      (merge out (reader-fn expanded-k v))
      out)))

(defn ^:private apply-metas
  ([ns t default init-map]
   (apply-metas ns t init-map nil))
  ([ns t default init-map reader-map]
   (let [meta-data (merge default (meta t))]
     (reduce-kv (fn [a k v]
                  (let [{:keys [new-k new-v]}
                        (find-and-run-reader reader-map ns k v)]
                    (assoc a new-k new-v)))
                init-map
                meta-data))))

(defn ^:private conj-type
  [a t default]
  (conj a (apply-metas
           "type" t default
           {:db/id (get-temp-id! t)
            :type/name (str t)}
           {:implements implements-reader})))

(defn ^:private conj-params
  [a t field params default]
  (reduce (fn [accum param]
            (conj accum (apply-metas
                         "param" param default
                         {:param/name (str param)
                          :param/parent {:db/id (get-temp-id! t field)}}
                         {:type (create-type-reader "param")
                          :tag (create-type-reader "param")})))
          a params))

(defn ^:private conj-fields
  [a t fields default]
  (loop [accum a
         field (first fields)
         last-field nil
         next-fields (next fields)]
    (if (nil? field)
      accum
      (let [new-accum
            (cond
              (symbol? field)
              (conj accum (apply-metas
                           "field" field default
                           {:db/id (get-temp-id! t field)
                            :field/name (str field)
                            :field/parent {:db/id (get-temp-id! t)}}
                           {:type (create-type-reader "field")
                            :tag (create-type-reader "field")}))

              (seqable? field)
              (conj-params accum t last-field field default)
              
              :default
              accum)]
        (recur new-accum
               (first next-fields)
               field
               (next next-fields))))))

(defn ^:private parse-types
  [accum types]
  (let [has-default? (= (first types) 'default)
        real-types (if has-default? (next types) types)
        default (if has-default? (meta (first types)))]
    (loop [a accum
           t (first real-types)
           fields (second real-types)
           next-t (next (next real-types))]
      (if-not (nil? t)
        (recur (-> a
                   (conj-type t default)
                   (conj-fields t fields default))
               (first next-t)
               (second next-t)
               (next (next next-t)))
        a))))

(defn ^:private parse-type-groups
  [accum type-groups]
  (reduce (fn [a type-group]
            (parse-types a type-group))
          accum
          type-groups))

(defn ^:private create-primitive-types
  [accum]
  (reduce (fn [a i]
            (conj a {:db/id (get-temp-id! i)
                     :type/name (str i)}))
          accum '[String Float Integer Boolean DateTime ID]))

(defn ^:private internal-schema
  [source-schemas]
  (-> []
      create-primitive-types
      (parse-type-groups source-schemas)))

;;TODO
(defn ^:private is-schema-valid?
  [schema] 
  true)

(defn ^:private ensure-meta-db
  [schema]
  #_(clojure.pprint/pprint schema)
  (let [conn (d/create-conn meta-schema)]
    (d/transact! conn schema)
    conn))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn init-schema [source-schema & others]
  (reset-temp-id-state!)
  (let [source-schemas (conj others source-schema)
        schema (internal-schema source-schemas)] 
    (if (is-schema-valid? schema)
      (ensure-meta-db schema))))

;; FIXME: make it recursive and with the group-types organized
#_(defn init-path [path & others]
    (let [paths (-> others flatten (conj path) flatten)]
      (-> paths
          schema-files
          reduce-all-files
          init-schema)))


(def c (engine/init-schema
        '[^{:datomic/tag true}
          default
          A [f [p]] B [f [p]]]
        '[^{:sql/tag true}
          default
          C [f [p]] [D [f [p]]]]))
