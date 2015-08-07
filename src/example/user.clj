(ns example.user
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [ib5k.component.ctr :as ctr]
            [juxt.datomic.extras :refer (DatabaseReference DatomicConnection as-conn as-db)]
            [schema.core :as s]
            [schema.utils :refer [class-schema]])
  (:import [datomic Connection]))

(s/defn get-user :- {:db/id s/Num
                     :user/username s/Str
                     :user/favorite-color
                     {:db/id s/Num
                      :db/ident (s/enum :color/red :color/yellow :color/blue)}}
  [database :- (s/protocol DatabaseReference)
   username :- s/Str]
  (println ";; get-user" username)
  (let [db (as-db database)]
    (some->> (d/q '[:find ?e
                    :in $ ?username
                    :where
                    [?e :user/username ?username]]
                  db username)
             ffirst
             (d/pull db '[*]))))

(s/defn add-user
  [database :- (s/protocol DatomicConnection)
   username :- s/Str
   favorite-color :- s/Str]
  (println ";; add-user" username favorite-color)
  (d/transact (as-conn database) [{:user/username username
                                   :user/favorite-color favorite-color}]))

(s/defrecord ExampleComponent
    [admin-name :- s/Str
     cache :- clojure.lang.Atom
     database :- (s/both (s/protocol DatomicConnection)
                         (s/protocol DatabaseReference))]
  component/Lifecycle
  (start [this]
    (println ";; Starting ExampleComponent")
    (let [admin (get-user database admin-name)]
      (println "admin" admin)
      (assoc this :admin admin)))
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
