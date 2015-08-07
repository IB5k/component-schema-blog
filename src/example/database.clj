(ns example.database
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [ib5k.component.ctr :as ctr]
            [juxt.datomic.extras :refer (DatabaseReference DatomicConnection as-conn as-db)]
            [schema.core :as s])
  (:import [datomic Connection]))

(s/defrecord Database
    [uri :- s/Str
     conn :- (s/maybe datomic.Connection)]
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
    (assoc component :conn nil))
  DatomicConnection
  (as-conn [this]
    (:conn this))
  DatabaseReference
  (as-db [this]
    ;; datomic.Connection implements DatabaseReference
    (as-db (as-conn this))))

(def new-database
  (-> map->Database
      (ctr/wrap-class-validation Database)
      (ctr/wrap-kargs)))
