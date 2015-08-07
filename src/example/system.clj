(ns example.system
  (:require [example.database :refer [new-database]]
            [example.user :refer [example-component]]
            [com.stuartsierra.component :as component]
            [ib5k.component.ctr :as ctr]
            [milesian.bigbang :refer [expand]]
            [schema.core :as s]))

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

(defn start
  [system]
  (s/with-fn-validation ;; force validation of all functions in the system. All validation can be disabled at compile time for production using (s/set-compile-fn-validation! false)
    (expand system {:before-start [[ctr/validate-class]]
                    :after-start [[ctr/validate-class]]})))
