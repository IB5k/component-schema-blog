(ns example.user
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [schema.core :as s])
  (:import [datomic.peer Connection]))

(s/defschema DBSchema
  {:connection datomic.peer.Connection})

(s/defn get-user :- {:db/id s/Num
                     :user/username s/Str
                     :user/favorite-color s/Keyword}
  [database :- DBSchema
   username :- s/Str]
  (println ";; get-user" username)
  (let [db (d/db (:connection database))]
    (->> (d/q '[:find ?e
                :in $ ?username
                :where
                [?e :user/username ?username]]
              db username)
         (d/pull db '[*]))))

(s/defn add-user
  [database :- DBSchema
   username :- s/Str
   favorite-color :- s/Str]
    (println ";; add-user" username favorite-color)
  (d/transact (:connection database) [{:user/username username
                                       :user/favorite-color favorite-color}]))

(s/defrecord ExampleComponent
    [options :- {}
     cache :- clojure.lang.IAtom
     database :- DBSchema]
  component/Lifecycle
  (start [this]
    (println ";; Starting ExampleComponent")
    (assoc this :admin (get-user database "admin")))
  (stop [this]
    (println ";; Stopping ExampleComponent")
    this))

(defn example-component [config-options]
  (map->ExampleComponent {:options config-options
                          :cache (atom {})}))
