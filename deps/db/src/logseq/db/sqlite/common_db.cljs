(ns logseq.db.sqlite.common-db
  "Common sqlite db fns for browser and node"
  (:require [datascript.core :as d]
            ["path" :as node-path]
            [clojure.string :as string]
            [logseq.db.sqlite.util :as sqlite-util]
            [logseq.common.util.date-time :as date-time-util]
            [logseq.common.util :as common-util]))

(comment
  (defn- get-built-in-files
    [db]
    (let [files ["logseq/config.edn"
                 "logseq/custom.css"
                 "logseq/custom.js"]]
      (map #(d/pull db '[*] [:file/path %]) files))))

(defn get-all-pages
  [db]
  (->> (d/datoms db :avet :block/name)
       (map (fn [e]
              (d/pull db '[*] (:e e))))))

(defn get-all-files
  [db]
  (->> (d/datoms db :avet :file/path)
       (map (fn [e]
              {:db/id (:e e)
               :file/path (:v e)
               :file/content (:file/content (d/entity db (:e e)))}))))

(defn- with-block-refs
  [db block]
  (update block :block/refs (fn [refs] (map (fn [ref] (d/pull db '[*] (:db/id ref))) refs))))

(defn with-parent-and-left
  [db block]
  (cond
    (:block/name block)
    block
    (:block/page block)
    (let [left (when-let [e (d/entity db (:db/id (:block/left block)))]
                 (select-keys e [:db/id :block/uuid]))
          parent (when-let [e (d/entity db (:db/id (:block/parent block)))]
                   (select-keys e [:db/id :block/uuid]))]
      (->>
       (assoc block
              :block/left left
              :block/parent parent)
       (common-util/remove-nils-non-nested)
       (with-block-refs db)))
    :else
    block))

(defn- mark-block-fully-loaded
  [b]
  (assoc b :block.temp/fully-loaded? true))

(defn get-block-and-children
  [db name children?]
  (let [uuid? (common-util/uuid-string? name)
        block (when uuid?
                (let [id (uuid name)]
                  (d/entity db [:block/uuid id])))
        get-children (fn [children]
                       (let [long-page? (> (count children) 500)]
                         (if long-page?
                           (map (fn [e]
                                  (select-keys e [:db/id :block/uuid :block/page :block/left :block/parent :block/collapsed?]))
                                children)
                           (->> (d/pull-many db '[*] (map :db/id children))
                                (map #(with-block-refs db %))
                                (map mark-block-fully-loaded)))))]
    (if (and block (not (:block/name block))) ; not a page
      (let [block' (->> (d/pull db '[*] (:db/id block))
                        (with-parent-and-left db)
                        (with-block-refs db)
                        mark-block-fully-loaded)]
        (cond->
         {:block block'}
          children?
          (assoc :children (get-children (:block/_parent block)))))
      (when-let [block (or block (d/entity db [:block/name name]))]
        (cond->
         {:block (-> (d/pull db '[*] (:db/id block))
                     mark-block-fully-loaded)}
          children?
          (assoc :children
                 (if (contains? (:block/type block) "whiteboard")
                   (->> (d/pull-many db '[*] (map :db/id (:block/_page block)))
                        (map #(with-block-refs db %))
                        (map mark-block-fully-loaded))
                   (get-children (:block/_page block)))))))))

(defn get-latest-journals
  [db n]
  (let [date (js/Date.)
        _ (.setDate date (- (.getDate date) (dec n)))
        today (date-time-util/date->int (js/Date.))]
    (->>
     (d/q '[:find [(pull ?page [*]) ...]
            :in $ ?today
            :where
            [?page :block/name ?page-name]
            [?page :block/journal? true]
            [?page :block/journal-day ?journal-day]
            [(<= ?journal-day ?today)]]
          db
          today)
     (sort-by :block/journal-day)
     (reverse)
     (take n))))

(defn get-structured-blocks
  [db]
  (->> (d/datoms db :avet :block/type)
       (keep (fn [e]
               (when (contains? #{"closed value"} (:v e))
                 (d/pull db '[*] (:e e)))))))

(defn get-initial-data
  "Returns initial data"
  [db]
  (let [latest-journals (get-latest-journals db 3)
        all-files (get-all-files db)
        structured-blocks (get-structured-blocks db)]
    (concat latest-journals all-files structured-blocks)))

(defn restore-initial-data
  "Given initial sqlite data and schema, returns a datascript connection"
  [data schema]
  (let [conn (d/create-conn schema)]
    (d/transact! conn data)
    conn))

(defn create-kvs-table!
  "Creates a sqlite table for use with datascript.storage if one doesn't exist"
  [sqlite-db]
  (.exec sqlite-db "create table if not exists kvs (addr INTEGER primary key, content TEXT)"))

(defn get-storage-conn
  "Given a datascript storage, returns a datascript connection for it"
  [storage schema]
  (or (d/restore-conn storage)
      (d/create-conn schema {:storage storage})))

(defn sanitize-db-name
  [db-name]
  (if (string/starts-with? db-name sqlite-util/file-version-prefix)
    (-> db-name
        (string/replace ":" "+3A+")
        (string/replace "/" "++"))
    (-> db-name
       (string/replace sqlite-util/db-version-prefix "")
       (string/replace "/" "_")
       (string/replace "\\" "_")
       (string/replace ":" "_"))));; windows

(defn get-db-full-path
  [graphs-dir db-name]
  (let [db-name' (sanitize-db-name db-name)
        graph-dir (node-path/join graphs-dir db-name')]
    [db-name' (node-path/join graph-dir "db.sqlite")]))
