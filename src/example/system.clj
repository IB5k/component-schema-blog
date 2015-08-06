(ns example.system
  (:require [com.stuartsierra.component :as component]
            [example.database :refer [new-database]]
            [example.user :refer [example-component]]))

(defn example-system [config-options]
  (let [{:keys [uri]} config-options]
    (component/system-map
      :db (new-database :uri uri)
      :app (component/using
             (example-component)
             {:database :db}))))

(defn new-system
  []
  (example-system {:uri "datomic:dev://localhost:4334/example"}))
