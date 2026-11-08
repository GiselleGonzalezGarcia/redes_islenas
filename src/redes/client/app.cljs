(ns redes.client.app
  "Entry point: load payload into Datahike, mount the Spindel app."
  (:require [org.replikativ.spindel.core :as s :refer-macros [spin]]
            [org.replikativ.spindel.effects.track :refer [track]]
            [org.replikativ.spindel.effects.await :refer [await]]
            [org.replikativ.spindel.incremental.interval :as iv]
            [org.replikativ.spindel.dom.elements :as el :refer-macros [div p h1 main]]
            [org.replikativ.spindel.dom.browser :as browser]
            [org.replikativ.spindel.dom.render :as render]
            [org.replikativ.spindel.dom.router :as rtr]
            [org.replikativ.spindel.engine.core :as ec]
            [taoensso.trove :as trove]
            [taoensso.trove.console :as trove-console]
            [redes.client.state :as state]
            [redes.client.db :as db]
            [redes.client.i18n :refer [t]]
            [redes.client.views.common :as c]
            [redes.client.views.inicio :as inicio]
            [redes.client.views.explorar :as explorar]
            [redes.client.views.persona :as persona]
            [redes.client.views.travesia :as travesia]
            [redes.client.views.datos :as datos]
            [redes.client.views.fuentes :as fuentes]
            [redes.client.views.lugares :as lugares]))

; library trace/debug logging floods the console and slows rendering
(set! trove/*log-fn* (trove-console/get-log-fn {:min-level :warn}))

(defn placeholder [lang k]
  (div {:class "pagina"} (h1 (t lang k)) (p "…")))

(defn app-spin []
  (spin
    (let [route (iv/get-new (track (:signal state/app-router)))
          lang (iv/get-new (track state/lang-signal))
          nm (or (:name route) :inicio)
          content (case nm
                    :inicio (await (inicio/inicio-spin))
                    :explorar (await (explorar/explorar-spin))
                    :persona (await (persona/persona-spin (get-in route [:path-params :id])))
                    :travesia (await (travesia/travesia-spin))
                    :pueblos (await (lugares/pueblos-spin))
                    :pueblo (await (lugares/pueblo-spin (get-in route [:path-params :id])))
                    :barcos (await (lugares/barcos-spin))
                    :barco (await (lugares/barco-spin (get-in route [:path-params :id])))
                    :datos (await (datos/datos-spin))
                    :fuentes (await (fuentes/fuentes-spin))
                    :acerca (await (fuentes/acerca-spin))
                    (placeholder lang :nav/inicio))]
      (div {:class "app"}
        (c/barra lang nm)
        (main content)
        (when-not (= nm :travesia) (c/pie lang))))))

(defonce router-stop (atom nil))
(defonce loaded? (atom false))

(defn mount! []
  (state/with-ctx
    (fn []
      (when-let [stop @router-stop] (stop))
      (reset! router-stop (rtr/start! state/app-router state/context))
      (let [root (js/document.getElementById "app")]
        (set! (.-innerHTML root) "")
        (render/render-spin! root (app-spin) (browser/make-dom-discharge js/document))))))

(defn load-data! []
  (when-not @loaded?
    (reset! loaded? true)
    (let [t0 (js/performance.now)]
      (db/load! state/base-path
                (fn [index]
                  (js/console.info "index ready in" (js/Math.round (- (js/performance.now) t0)) "ms")
                  (state/with-ctx #(reset! state/index-signal index)))
                (fn [dbv timings]
                  (js/console.info "database ready" (pr-str (update-vals timings js/Math.round)))
                  (state/with-ctx #(reset! state/db-signal dbv)))
                (fn [e] (js/console.error "loading failed" (str e)))))))

(defn ^:export init! []
  (mount!)
  (load-data!))

(defn ^:export reload! []
  (mount!))
