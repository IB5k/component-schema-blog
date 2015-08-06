(ns example.database
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [schema.core :as s])
  (:import [datomic.peer Connection]))

(s/defrecord Database
    [uri :- s/Str
     conn :- (s/maybe Connection)]
  component/Lifecycle
  (start [component]
    (println ";; Starting database")
    (let [conn (d/connect uri)]
      (assoc component :conn conn)))
  (stop [component]
    (println ";; Stopping database")
    (d/release conn)
    (d/shutdown false)
    (assoc component :conn nil)))

(s/defn new-database
  [uri :- s/Str]
  (map->Database {:uri uri}))
