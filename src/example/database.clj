(ns example.database
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [schema.core :as s]
            [ib5k.component.ctr :as ctr])
  (:import [datomic.peer Connection]))

(s/defrecord Database
    [uri :- s/Str
     conn :- (s/maybe Connection)]
  component/Lifecycle
  (start [component]
    (println ";; Starting database")
    (if conn
      component
      (let [conn (d/connect uri)]
       (assoc component :conn conn))))
  (stop [component]
    (println ";; Stopping database")
    (when conn
      (d/release conn))
    (d/shutdown false)
    (assoc component :conn nil)))

(def new-database
  (-> map->Database
      (ctr/wrap-class-validation Database)
      (ctr/wrap-kargs)))
