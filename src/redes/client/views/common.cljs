(ns redes.client.views.common
  "Shared view helpers: links, navigation bar, footer."
  (:require [org.replikativ.spindel.dom.elements :as el :refer-macros [a nav header footer p button div b i]]
            [redes.client.state :as state]
            [redes.client.i18n :refer [t]]))

(defn link
  "Router link: real href (open in new tab works), client-side navigation on click.
  Element macros only treat a literal map as attributes, hence the explicit keys."
  [{:keys [class key aria-current]} route params & children]
  (a {:href (state/href route params)
      :class class
      :key key
      :aria-current aria-current
      :on-click (fn [e]
                  (when-not (or (.-metaKey e) (.-ctrlKey e) (.-shiftKey e) (= 1 (.-button e)))
                    (.preventDefault e)
                    (state/navigate! route params)))}
     children))

(def nav-items [[:inicio :nav/inicio] [:explorar :nav/explorar] [:travesia :nav/travesia]
                [:datos :nav/datos] [:fuentes :nav/fuentes] [:acerca :nav/acerca]])

(defn barra [lang current]
  (header {:class "barra"}
    (link {:class "marca"} :inicio {} "Redes Isleñas")
    (nav {:aria-label "principal"}
      (for [[route k] (rest nav-items)]
        (link {:key (name route)
               :aria-current (when (or (= route current)
                                        (and (= route :explorar) (#{:persona :pueblos :pueblo :barcos :barco} current)))
                               "page")}
              route {} (t lang k))))
    (button {:class "idioma" :lang (if (= lang :es) "en" "es") :on-click (fn [_] (state/toggle-lang!))}
      (t lang :idioma))))

(def ciid-url "https://gisellegonzalezgarcia.github.io/ciid/")

(defn site-url [] (str (.-origin js/location) state/base-path "/"))

(defn pie [lang]
  (let [es? (= lang :es)]
    (footer {:class "pie"}
      (p {:class "credito"} (b "Redes Isleñas") " · Giselle González García (dir.), 2026")
      (p {:class "cita-sitio"}
        (if es? "Cómo citar: " "How to cite: ")
        "González García, Giselle (dir.). "
        (i (if es? "Redes Isleñas. Base de datos de la emigración canaria a América"
                   "Redes Isleñas. Base de datos de la emigración canaria a América [Database of Canarian emigration to the Americas]"))
        ", 2026. " (site-url)))))

(defn fmt-n [n]
  (when n (.toLocaleString n "es-ES")))
