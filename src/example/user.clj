(ns example.user
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [schema.core :as s]
            [ib5k.component.ctr :as ctr])
  (:import [datomic.peer Connection]))

(s/defschema DBSchema
  {:uri s/Str
   :connection datomic.peer.Connection})

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
    [admin-name :- s/Str
     cache :- clojure.lang.Atom
     database :- DBSchema]
  component/Lifecycle
  (start [this]
    (ctr/validate-class this)
    (println ";; Starting ExampleComponent")
    (assoc this :admin (get-user database admin-name)))
  (stop [this]
    (println ";; Stopping ExampleComponent")
    this))

(def example-component
  (-> map->ExampleComponent
      (ctr/wrap-class-validation ExampleComponent)
      (ctr/wrap-using [:database])
      (ctr/wrap-defaults {:admin-name "admin"
                          :cache (atom {})})
      (ctr/wrap-kargs)))
