(ns example.system
  (:require [example.database :refer [new-database]]
            [example.user :refer [example-component]]
            [com.stuartsierra.component :as component]
            [ib5k.component.ctr :as ctr]
            [ib5k.component.schema :as component-schema]
            [juxt.datomic.extras :refer (DatabaseReference DatomicConnection)]
            [milesian.bigbang :refer [expand]]
            [schema.core :as s]))

(defn example-system [config-options]
  (let [{:keys [uri]} config-options]
    (-> (component/system-map
         :db (new-database :uri uri)
         :app (example-component))
        (component-schema/system-using-schema
         {:app {:database (s/both (s/protocol DatomicConnection)
                                  (s/protocol DatabaseReference))}}))))

(defn new-system
  []
  (example-system {:uri "datomic:dev://localhost:4334/example"}))

(defn start
  [system]
  (s/with-fn-validation
    (expand system {:before-start [[ctr/validate-class]]
                    :after-start [[ctr/validate-class]]})))
