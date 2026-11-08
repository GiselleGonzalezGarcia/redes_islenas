(ns redes.client.views.explorar
  "Search the register."
  (:require [org.replikativ.spindel.core :as s :refer-macros [spin]]
            [org.replikativ.spindel.effects.track :refer [track]]
            [org.replikativ.spindel.incremental.interval :as iv]
            [org.replikativ.spindel.dom.elements :as el
             :refer-macros [div span h1 p input button table thead tbody tr th td form]]
            [redes.client.state :as state]
            [redes.client.db :as db]
            [redes.client.i18n :refer [t]]
            [redes.client.views.common :as c]
            [redes.client.views.lugares :as lugares]))

(def page-size 50)

(defn search-input [lang q]
  (form {:class "busca" :role "search"
         :on-submit (fn [e] (.preventDefault e))}
    (input {:type "search" :name "q" :value q
            :aria-label (t lang :busca/placeholder)
            :placeholder (t lang :busca/placeholder)
            :on-input (fn [e] (state/set-search! (.. e -target -value)))})))

(defn row [r]
  (tr {:key (:id r)
       :on-click (fn [_] (state/navigate! :persona {:id (:id r)}))}
    (td {:class "nombre"} (c/link {} :persona {:id (:id r)} (:name r)))
    (td (:origin r))
    (td {:class "opcional"} (:residence r))
    (td (:dest r))
    (td {:class "num"} (:year r))
    (td {:class "num opcional"} (:age r))))

(defn explorar-spin []
  (spin
    (let [lang (iv/get-new (track state/lang-signal))
          index (iv/get-new (track state/index-signal))
          {:keys [q page]} (iv/get-new (track state/search-signal))]
      (if-not index
        (div {:class "pagina"} (p (t lang :cargando)))
        (let [hits (db/search index q)
              n (count hits)
              pages (max 1 (js/Math.ceil (/ n page-size)))
              page (min page (dec pages))
              shown (subvec hits (* page page-size) (min n (* (inc page) page-size)))]
          (div {:class "pagina"}
            (lugares/sub-nav lang :explorar)
            (h1 (t lang :nav/explorar))
            (search-input lang q)
            (p {:class "cuenta"} (str (c/fmt-n n) " " (t lang :busca/resultados)))
            (if (zero? n)
              (p (t lang :busca/vacio))
              (table {:class "registro"}
                (thead
                  (tr
                    (th (t lang :col/nombre)) (th (t lang :col/origen))
                    (th {:class "opcional"} (t lang :col/vecino)) (th (t lang :col/destino))
                    (th {:class "num"} (t lang :col/anio)) (th {:class "num opcional"} (t lang :col/edad))))
                (tbody (map row shown))))
            (div {:class "paginas"}
              (button {:disabled (zero? page) :on-click (fn [_] (state/set-page! (dec page)))}
                (t lang :busca/anterior))
              (span (str (t lang :busca/pagina) " " (inc page) " / " pages))
              (button {:disabled (>= (inc page) pages) :on-click (fn [_] (state/set-page! (inc page)))}
                (t lang :busca/siguiente)))))))))
