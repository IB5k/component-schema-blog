(set-env!
 :repositories #(conj % ["my.datomic.com" {:url "https://my.datomic.com/repo"
                                           :creds {:username (System/getenv "DATOMIC_USER")
                                                   :password (System/getenv "DATOMIC_PASS")}}])
 :dependencies '[[com.stuartsierra/component "0.2.3"]
                 [com.datomic/datomic-free "0.9.5206"]
                 [org.clojure/clojure "1.8.0-alpha4"]
                 [ib5k/boot-component "0.1.2-SNAPSHOT"]
                 [ib5k/component-schema "0.1.2-SNAPSHOT"]
                 [jeluard/boot-notify "0.2.0"]
                 [prismatic/schema "0.4.3"]]
 :source-paths #{"src"}
 :resource-paths #(conj % "resources"))

(require
 '[boot-component.reloaded :refer :all]
 '[jeluard.boot-notify :refer :all])

(deftask dev
  "watch and compile cljx, css, cljs, init cljs-repl and push changes to browser"
  []
  (comp
   (repl :server true)
   (watch)
   (notify)
   (reload-system :system-var 'example.system/new-system)))
