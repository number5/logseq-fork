(ns logseq.outliner.pipeline
  "Core fns for use with frontend.modules.outliner.pipeline"
  (:require [logseq.db.schema :as db-schema]
            [datascript.core :as d]
            [cognitect.transit :as t]
            [clojure.set :as set]))

(defn filter-deleted-blocks
  [datoms]
  (keep
   (fn [d]
     (when (and (= :block/uuid (:a d)) (false? (:added d)))
       (:v d)))
   datoms))

(defn datom->av-vector
  [db datom]
  (let [a (:a datom)
        v (:v datom)
        v' (cond
             (contains? db-schema/ref-type-attributes a)
             (when-some [block-uuid-datom (first (d/datoms db :eavt v :block/uuid))]
               [:block/uuid (str (:v block-uuid-datom))])

             (and (= :block/uuid a) (uuid? v))
             (str v)

             :else
             v)]
    (when (some? v')
      [a v'])))

(defn build-upsert-blocks
  [blocks deleted-block-uuids db-after]
  (let [t-writer (t/writer :json)]
    (->> blocks
         (remove (fn [b] (contains? deleted-block-uuids (:block/uuid b))))
         (map (fn [b]
                (let [datoms (d/datoms db-after :eavt (:db/id b))]
                  (assoc b :datoms
                         (->> datoms
                              (keep
                               (partial datom->av-vector db-after))
                              (t/write t-writer))))))
         (map (fn [b]
                (if-some [page-uuid (:block/uuid (d/entity db-after (:db/id (:block/page b))))]
                  (assoc b :page_uuid page-uuid)
                  b)))
         (map (fn [b]
                (let [uuid (or (:block/uuid b) (random-uuid))]
                  (assoc b :block/uuid uuid)))))))

;; non recursive query
(defn get-block-parents
  [db block-id {:keys [depth] :or {depth 100}}]
  (loop [block-id block-id
         parents (list)
         d 1]
    (if (> d depth)
      parents
      (if-let [parent (:block/parent (d/entity db [:block/uuid block-id]))]
        (recur (:block/uuid parent) (conj parents parent) (inc d))
        parents))))

(defn get-block-children-ids
  [db block-uuid]
  (when-let [eid (:db/id (d/entity db [:block/uuid block-uuid]))]
    (let [seen   (volatile! [])]
      (loop [steps          100      ;check result every 100 steps
             eids-to-expand [eid]]
        (when (seq eids-to-expand)
          (let [eids-to-expand*
                (mapcat (fn [eid] (map first (d/datoms db :avet :block/parent eid))) eids-to-expand)
                uuids-to-add (remove nil? (map #(:block/uuid (d/entity db %)) eids-to-expand*))]
            (when (and (zero? steps)
                       (seq (set/intersection (set @seen) (set uuids-to-add))))
              (throw (ex-info "bad outliner data, need to re-index to fix"
                              {:seen @seen :eids-to-expand eids-to-expand})))
            (vswap! seen (partial apply conj) uuids-to-add)
            (recur (if (zero? steps) 100 (dec steps)) eids-to-expand*))))
      @seen)))

;; TODO: it'll be great if we can calculate the :block/path-refs before any
;; outliner transaction, this way we can group together the real outliner tx
;; and the new path-refs changes, which makes both undo/redo and
;; react-query/refresh! easier.

;; TODO: also need to consider whiteboard transactions

;; Steps:
;; 1. For each changed block, new-refs = its page + :block/refs + parents :block/refs
;; 2. Its children' block/path-refs might need to be updated too.
(defn computer-block-path-refs
  [{:keys [db-before db-after]} blocks*]
  (let [blocks (remove :block/name blocks*)
        *computed-ids (atom #{})]
    (mapcat (fn [block]
              (when (and (not (@*computed-ids (:block/uuid block))) ; not computed yet
                         (not (:block/name block)))
                (let [parents (get-block-parents db-after (:block/uuid block) {})
                      parents-refs (->> (mapcat :block/path-refs parents)
                                        (map :db/id))
                      old-refs (if db-before
                                 (set (map :db/id (:block/path-refs (d/entity db-before (:db/id block)))))
                                 #{})
                      new-refs (set (concat
                                     (some-> (:db/id (:block/page block)) vector)
                                     (map :db/id (:block/refs block))
                                     parents-refs))
                      refs-changed? (not= old-refs new-refs)
                      children (get-block-children-ids db-after (:block/uuid block))
                            ;; Builds map of children ids to their parent id and :block/refs ids
                      children-maps (into {}
                                          (map (fn [id]
                                                 (let [entity (d/entity db-after [:block/uuid id])]
                                                   [(:db/id entity)
                                                    {:parent-id (get-in entity [:block/parent :db/id])
                                                     :block-ref-ids (map :db/id (:block/refs entity))}]))
                                               children))
                      children-refs (map (fn [[id {:keys [block-ref-ids] :as child-map}]]
                                           {:db/id id
                                                  ;; Recalculate :block/path-refs as db contains stale data for this attribute
                                            :block/path-refs
                                            (set/union
                                                   ;; Refs from top-level parent
                                             new-refs
                                                   ;; Refs from current block
                                             block-ref-ids
                                                   ;; Refs from parents in between top-level
                                                   ;; parent and current block
                                             (loop [parent-refs #{}
                                                    parent-id (:parent-id child-map)]
                                               (if-let [parent (children-maps parent-id)]
                                                 (recur (into parent-refs (:block-ref-ids parent))
                                                        (:parent-id parent))
                                                       ;; exits when top-level parent is reached
                                                 parent-refs)))})
                                         children-maps)]
                  (swap! *computed-ids set/union (set (cons (:block/uuid block) children)))
                  (concat
                   (when (and (seq new-refs)
                              refs-changed?)
                     [{:db/id (:db/id block)
                       :block/path-refs new-refs}])
                   children-refs))))
            blocks)))