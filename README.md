# Component Schema: Building Collaborative and Testable Systems

If you’re not already familiar with Stuart Sierra’s excellent [component](https://github.com/stuartsierra/component) library, you’re missing out on a powerful system abstraction for runtime dependency management. By compartmentalizing code into components, we can make relationships between different parts of code explicit. While this is great, it can be unclear exactly what function a dependency provides, and components must often know too much about their dependencies.

A complete repo of the code in this post is [available here](https://github.com/IB5k/component-schema-blog).

# Prismatic Schema

Prismatic’s [schema](https://github.com/Prismatic/schema) library provides utilities for validating the shape of data structures at runtime. 

In the case of component systems, we can use schemas to both document and validate systems at various points in their lifecycle. 

Let's modify the database example from [component](https://github.com/stuartsierra/component#user-content-creating-components) to connect to a [Datomic](http://www.datomic.com/) database

```clojure
;; example/database.clj
(ns example.database
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]))

(defrecord Database [uri connection]
  ;; Implement the Lifecycle protocol
  component/Lifecycle
  (start [component]
    (println ";; Starting database")
    (println ";; Opening database connection")
    (let [conn (d/connect uri)]
      ;; Return an updated version of the component with
      ;; the run-time state assoc'd in.
      (assoc component :connection conn)))
  (stop [component]
    (println ";; Stopping database")
    ;; In the 'stop' method, shut down the running
    ;; component and release any external resources it has
    ;; acquired.
    (println ";; Closing database connection")
    (d/release connection)
    (d/shutdown false)
    ;; Return the component, optionally modified. Remember that if you
    ;; dissoc one of a record's base fields, you get a plain map.
    (assoc component :connection nil)))

(defn new-database [uri]
  (map->Database {:uri uri}))
```

This is great. We can now use the database in another component. Following Stuart's examplem let's write functions to get and add users and use them to find the admin in another component. Let's ignore transacting initial database schema for now. 

```clojure
;; example/user.clj
(ns example.user
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]))

(defn get-user
  [database username]
  (println ";; get-user" username)
  (let [db (d/db (:connection database))]
    (->> (d/q '[:find ?e
                :in $ ?username
                :where
                [?e :user/username ?username]]
              db username)
         (d/pull db '[*]))))

(defn add-user
  [database username favorite-color]
  (println ";; add-user" username favorite-color)
  (d/transact (:connection database) [{:user/username username
                                       :user/favorite-color favorite-color}]))

(defrecord ExampleComponent [options cache database]
  component/Lifecycle
  (start [this]
    (println ";; Starting ExampleComponent")
    ;; In the 'start' method, a component may assume that its
    ;; dependencies are available and have already been started.
    (let [admin (get-user database "admin")]
      (println ";; admin" admin)
      (assoc this :admin admin)))
  (stop [this]
    (println ";; Stopping ExampleComponent")
    ;; Likewise, in the 'stop' method, a component may assume that its
    ;; dependencies will not be stopped until AFTER it is stopped.
    this))

(defn example-component [config-options]
  (map->ExampleComponent {:options config-options
                          :cache (atom {})}))
```

and our system

```clojure
;; example/system.clj
(ns example.system
  (:require [com.stuartsierra.component :as component]
            [example.database :refer [new-database]]
            [example.user :refer [example-component]]))

(defn example-system [config-options]
  (let [{:keys [uri]} config-options]
    (component/system-map
      :db (new-database uri)
      :app (component/using
             (example-component config-options)
             {:database  :db}))))
```

and let's try it in the repl

```clojure
(require '[datomic.api :as d])
;; create the database
(def uri "datomic:dev://localhost:4334/example")
(d/create-database uri)

(require '[example.system :refer [example-system]])
(require '[com.stuartsierra.component :as component])
;; create the system
(def system (example-system {:uri uri}))
;; start the system
(alter-var-root #'system component/start)
;; Starting database
;; Opening database connection
;; Starting ExampleComponent
;; get-user admin
;; admin nil
;;=> #<SystemMap>

(alter-var-root #'system component/stop)
;; Stopping ExampleComponent
;; Stopping database
;; Closing database connection
;;=> #<SystemMap>
```

Let's seed the database with a user

```clojure
(d/transact conn [{:db/id (d/tempid :db.part/db)
                   :db/ident :user/username
                   :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one
                   :db/unique :db.unique/identity ;; enable upsert
                   :db/doc "A user's username"
                   :db.install/_attribute :db.part/db}
                  {:db/id (d/tempid :db.part/db)
                   :db/ident :user/favorite-color
                   :db/valueType :db.type/ref
                   :db/isComponent true ;; make color a component so they're pulled with the user
                   :db/cardinality :db.cardinality/one
                   :db/doc "A user's favorite color"
                   :db.install/_attribute :db.part/db}
                  ;; enumerate primary colors
                  {:db/id (d/tempid :db.part/db)
                   :db/ident :color/red}
                  {:db/id (d/tempid :db.part/db)
                   :db/ident :color/yellow}
                  {:db/id (d/tempid :db.part/db)
                   :db/ident :color/blue}
                  {:db/id (d/tempid :db.part/db)}])
(d/transact conn [{:db/id (d/tempid :db.part/user)
                   :user/username "admin"
                   :user/favorite-color :color/red}])

(alter-var-root #'system component/start)
;; Starting database
;; Opening database connection
;; Starting ExampleComponent
;; get-user admin
;; admin {:db/id 17592186045438, :user/username "admin", :user/favorite-color :color/red}
```

This all makes sense, but what happens if someone changes the Database component so that the connection is stored differently?

```clojure
;; example/database.clj
(ns database
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]))

(defrecord Database [uri conn]
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

(defn new-database [uri]
  (map->Database {:uri uri}))
```

Can you spot the change? The ```:connection``` is now ```conn```. If we try to run the code in our user namespace now, we'll get a NullPointerException inside ```get-user```.

```clojure
;; boot.user =>
(def system (example-system {:uri uri}))
(alter-var-root #'system component/start)
;;=> Caused by java.lang.NullPointerException
;;   (No message)
;;                        api.clj:   60  datomic.api/db
;; boot.user1316278899018067968.clj:    4  example.database/get-user
;; boot.user1316278899018067968.clj:   -1  example.database/get-user
;; boot.user1316278899018067968.clj:    7  example.database.ExampleComponent/start
;;                  component.clj:    4  com.stuartsierra.component/eval10571/fn/G
;;                  component.clj:    4  com.stuartsierra.component/eval10571/fn/G
;;                       Var.java:  379  clojure.lang.Var/invoke
;;                       AFn.java:  154  clojure.lang.AFn/applyToHelper
;;                       Var.java:  700  clojure.lang.Var/applyTo
;;                       core.clj:  635  clojure.core/apply
;;                       core.clj:   -1  clojure.core/apply
;;                  component.clj:  116  com.stuartsierra.component/try-action
;;                  component.clj:   -1  com.stuartsierra.component/try-action
;;                  component.clj:  138  com.stuartsierra.component/update-system/fn
;;                  ArraySeq.java:  114  clojure.lang.ArraySeq/reduce
;;                       core.clj: 6521  clojure.core/reduce
;;                       core.clj:   -1  clojure.core/reduce
;;                  component.clj:  134  com.stuartsierra.component/update-system
;;                  component.clj:   -1  com.stuartsierra.component/update-system
;;                    RestFn.java:  445  clojure.lang.RestFn/invoke
;;                  component.clj:  162  com.stuartsierra.component/start-system
;;                  component.clj:   -1  com.stuartsierra.component/start-system
;;                  component.clj:  160  com.stuartsierra.component/start-system
;;                  component.clj:   -1  com.stuartsierra.component/start-system
;;                  component.clj:  177  com.stuartsierra.component.SystemMap/start
;;                  component.clj:    4  com.stuartsierra.component/eval10571/fn/G
;;                  component.clj:    4  com.stuartsierra.component/eval10571/fn/G
;;                       AFn.java:  154  clojure.lang.AFn/applyToHelper
;;                       AFn.java:  144  clojure.lang.AFn/applyTo
;;                       Var.java:  303  clojure.lang.Var/alterRoot
;;                       core.clj: 5278  clojure.core/alter-var-root
;;                       core.clj:   -1  clojure.core/alter-var-root
;;                    RestFn.java:  425  clojure.lang.RestFn/invoke
;; boot.user1316278899018067968.clj:    1  example.database/eval181618
;; boot.user1316278899018067968.clj:   -1  example.database/eval181618
```

Is there a way that we can ensure that component provide data the way we expect? Yes! Let's use Prismatic schema.


```clojure
;; example/database.clj
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

;; example/user.clj
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
```

That looks much clearer. But if you run the above code, you'll still get the same error. Why? Schemas are not validated at runtime by default. To force validation code, we could wrap the system start function. 

```clojure
(def system (example-system {:uri uri}))
(s/with-fn-validation (alter-var-root #'system component/start))
```
