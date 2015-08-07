# Component Schema: Building Collaborative and Testable Systems

If you’re not already familiar with Stuart Sierra’s excellent [component](https://github.com/stuartsierra/component) library, you’re missing out on a powerful system abstraction for runtime dependency management. By compartmentalizing code into components, we can make relationships between different parts of code explicit. While this is great, it can be unclear exactly what function a dependency provides, and components must often know too much about their dependencies.

Prismatic’s [schema](https://github.com/Prismatic/schema) library provides utilities for validating the shape of data structures at runtime.

In the case of component systems, we can use schemas to both document and validate systems at various points in their lifecycle. [Component Schema](https://github.com/IB5k/component-schema) provides tools for integrating schema into component systems. In this post, we'll walk through some of the rationale, implementation, and benefits.

A complete repo of the code in this post is [available here](https://github.com/IB5k/component-schema-blog).

Let's modify the database example from [component](https://github.com/stuartsierra/component#user-content-creating-components) to connect to a [Datomic](http://www.datomic.com/) database.

# Example System

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
    (some->> (d/q '[:find ?e
                    :in $ ?username
                    :where
                    [?e :user/username ?username]]
                  db username)
             ffirst
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

(defn new-system
  []
  (example-system {:uri "datomic:dev://localhost:4334/example"}))
```

and let's try it in the repl. Let's use [boot-component](https://github.com/IB5k/boot-component) to handled the [reloaded](https://github.com/stuartsierra/reloaded) workflow.

```clojure
;; boot.user>
(require '[datomic.api :as d])
;; create the database
(def uri "datomic:dev://localhost:4334/example")
(d/create-database uri)

(in-ns 'boot-component.reloaded)
;; boot-component.reloaded>
(reset)
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
(in-ns 'boot.user)
;; boot.user>
(def conn (d/connect uri))
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

(in-ns 'boot-component.reloaded)
;; boot-component.reloaded>
(reset)
;; Starting database
;; Opening database connection
;; Starting ExampleComponent
;; get-user admin
;; admin {:db/id 17592186045438, :user/username "admin", :user/favorite-color :color/red}
```

This all makes sense, but what happens if someone changes the Database component so that the connection is stored differently?

# Break the Code (who done it?)

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
;; boot-component.reloaded>
(reset)
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

# Component Schema

```clojure
;; example/database.clj
(ns example.database
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [schema.core :as s])
  (:import [datomic Connection]))

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
  (:import [datomic Connection]))

(s/defschema DBSchema
  {:uri s/Str
   :connection datomic.Connection})

(s/defn get-user :- {:db/id s/Num
                     :user/username s/Str
                     :user/favorite-color
                     {:db/id s/Num
                      :db/ident (s/enum :color/red :color/yellow :color/blue)}}
  [database :- DBSchema
   username :- s/Str]
  (println ";; get-user" username)
  (let [db (d/db (:connection database))]
    (some->> (d/q '[:find ?e
                    :in $ ?username
                    :where
                    [?e :user/username ?username]]
                  db username)
             ffirst
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
     cache :- clojure.lang.Atom
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
;; boot-component.reloaded>
(require '[schema.core :as s])
(s/with-fn-validation (alter-var-root #'system component/start))
;; => Unhandled clojure.lang.ExceptionInfo
;;    Caused by clojure.lang.ExceptionInfo
;;    Input to get-user does not match schema: [(named {:conn
;;    disallowed-key, :uri disallowed-key, :connection
;;    missing-required-key} database) nil]
;;    {:schema
;;     [{:schema {:connection datomic.Connection},
;;       :optional? false,
;;       :name database}
;;      {:schema java.lang.String, :optional? false, :name username}],
;;     :value
;;     [{:uri "datomic:dev://localhost:4334/example",
;;       :conn
;;       #<Connection {:db-id "example-6b6565c3-ba0b-4013-84cc-4805f837643f", :index-rev 0, :basis-t 1002, :next-t 1003, :unsent-updates-queue 0, :pending-txes 0}>}
;;      "admin"],
;;     :type :schema.core/error,
;;     :error
;;     [(named {:conn disallowed-key, :uri disallowed-key, :connection missing-required-key} database)
;;      nil]}
```

Much better, but instead of waiting for a method call (which might happen after your system starts) to error, we want to know that our system is badly formed immediately. Fail fast, fail often! We can do this by progressively validating the contruction of our system before start.

# Constructor Schema

The first point of validation are arguments passed into a component when it is first constructed. In the case of our database, we have a single argument, ```uri :- s/Str```. Let's use the helper functions in [ib5k.component.ctr](https://github.com/IB5k/component-schema/blob/master/src/ib5k/component/ctr.cljc) to build a more robust constructor.

```clojure
;; example/database.clj
(def new-database
  (-> map->Database
      ;; throw an error if the passed in arguments don't conform to the class schema
      (ctr/wrap-class-validation Database)
      ;; allow optional keyword arguments
      (ctr/wrap-kargs)))
```

Let's also update our example component to take the name of the admin as an argument

```clojure
;; example/user.clj
(s/defrecord ExampleComponent
    [admin-name :- s/Str
     cache :- clojure.lang.Atom
     database :- DBSchema]
  component/Lifecycle
  (start [this]
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
```

And our system file to call them correctly. Note that by wraping the constructors with ```wrap-kargs```, we can pass options to components either as maps or keyword arguments.

```clojure
;; example/system.clj
(defn example-system [config-options]
  (let [{:keys [uri]} config-options]
    (component/system-map
      :db (new-database :uri uri) ;; or (new-database {:uri uri})
      :app (component/using
             (example-component) ;; same as (example-component {})
             {:database :db}))))
```

To check if things are working, try creating a system where the database uri isn't a string, you should get an error.

```clojure
;; example.system>
(example-system {:uri :not-a-string})
;;    Unhandled clojure.lang.ExceptionInfo
;;    Value does not match schema: {:uri (not (instance? java.lang.String
;;    :not-a-string))}
;;    {:error {:uri (not (instance? java.lang.String :not-a-string))},
;;     :value {:uri :not-a-string},
;;     :schema {:uri java.lang.String},
;;     :type :schema.core/error}

```

Very nice! When working in teams, it's becomes especially important to produce failures when systems are configured incorrectly. It's even better when the errors tell you exactly what's wrong!

# Dependency Validation

Since component dependencies are assoc'd into components before their start function is called, we can't validate them at construction time, but instead need a way to validate them when at the time the components are started. The easiest way is to place a validation call inside component start.

```clojure
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

;; boot.component.reloaded>
(reset)
;;=>  Unhandled clojure.lang.ExceptionInfo
;;    Caused by clojure.lang.ExceptionInfo
;;    Value does not match schema: {:database {:conn disallowed-key,
;;    :connection missing-required-key}}
;;    {:error
;;     {:database {:conn disallowed-key, :connection missing-required-key}},
;;     :value
;;     {:database
;;      {:uri "datomic:dev://localhost:4334/example",
;;       :conn
;;       #<Connection {:db-id "example-090399a6-cadc-4177-ad07-f0ecc7cd33e4", :index-rev 0, :basis-t 63, :next-t 1000, :unsent-updates-queue 0, :pending-txes 0}>},
;;      :cache #<Atom@2085bdb0: {}>,
;;      :admin-name "admin"},
;;     :schema
;;     {:admin-name java.lang.String,
;;      :cache clojure.lang.Atom,
;;      :database
;;      {:uri java.lang.String, :connection datomic.Connection}},
;;     :type :schema.core/error}
```

Finally we get the error we were looking for! Now we can clearly see what's wrong. The connection is named incorrectly! Before we fix the error, let's decomplete component validation from the components themselves. Components shouldn't need to know that they're being validated, and having to remember to stick the validation call in Lifecycle is a pain. To do this, we're going to use [milesian.bigbang/expand](https://github.com/milesian/BigBang/blob/master/src/milesian/bigbang.clj#L4), a replacement for ```component/start``` that allows functions to be composed before and after start.

```clojure
;; example/system.clj
(ns example.system
  (:require [milesian.bigbang :refer [expand]]))
;; [...]

(defn start
  [system]
  (s/with-fn-validation ;;
    (expand system {:before-start [[ctr/validate-class]]
                    :after-start [[ctr/validate-class]]})))
```

We can now use this as our start function for our system. Using boot-component, we can set the ```reload-system``` task option ```:start-var 'example.system/start```. We also wrap the start function with-fn-validation force to validation of all functions in the system. All validation can be disabled at compile time for production using ```(s/set-compile-fn-validation! false)```. Trying it at the repl, we get the same error as before.

Now let's go back and fix our bug. Instead of just renaming the connection back to what it should be, let's wrap it in a protocol to hide this kind of implementation detail and prevent future errors. Juxt's [datomic-extras](https://github.com/juxt/datomic-extras/) provides two protocols that we can extend to make our database component more substantially more flexible, ```DatomicConnection``` and ```DatabaseReference```.

```clojure
;; example/database.clj
(ns example.database
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [schema.core :as s]
            [ib5k.component.ctr :as ctr]
            [juxt.datomic.extras :refer (DatabaseReference DatomicConnection as-conn as-db)])
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
```

Now in our example component, we can be more explicit about our database dependency, without having to know about the way the component is implemented.

```clojure
(s/defn get-user
  [database :- (s/protocol DatabaseReference)
   username :- s/Str]
  ;; [...]
  )

(s/defn add-user
  [database :- (s/protocol DatomicConnection)
   username :- s/Str
   favorite-color :- s/Str]
  ;; [...]
  )

(s/defrecord ExampleComponent
    [admin-name :- s/Str
     cache :- clojure.lang.Atom
     database :- (s/both (s/protocol DatomicConnection)
                         (s/protocol DatabaseReference))]
    ;; [...]
  )
```

The last thing we can do is go back to our system constructor, and use schema to define our dependencies.

```clojure
;; example/system.clj
(defn example-system [config-options]
  (let [{:keys [uri]} config-options]
    (-> (component/system-map
         :db (new-database :uri uri)
         :app (example-component))
        (component-schema/system-using-schema
         {:app {:database (s/both (s/protocol DatomicConnection)
                                  (s/protocol DatabaseReference))}}))))
```

Our system and components define their dependencies in terms of schema, and everything is validated on start! This allows individual developers a great deal more autonomy while developing in larger teams. A developer could be tasked with creating a component that satisfies a particular schema, and their choice of key names, implementation style, etc. is all isolated from the functioning of the system.

In the next post we'll look at how to create larger, modular component systems that leverage protocols and schemas to create complex system wide behaviors where component logic is isolated to its internal workings.

Source code for the resulting project is [available here](https://github.com/IB5k/component-schema-blog).
