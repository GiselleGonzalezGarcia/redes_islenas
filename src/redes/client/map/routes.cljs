(ns redes.client.map.routes
  "Stylised sailing routes from the Canarian ports to the American ports.
  Waypoints follow the trade-wind route (south-west to ~20°N, then west through
  the Antilles); they are illustrative, not reconstructed tracks.")

;; [lon lat]
(def port-exit
  "From each departure port around the south of its island into open sea."
  {"port-sc-tenerife" [[-16.25 28.46] [-16.15 28.2] [-16.45 27.9] [-17.0 27.8]]
   "port-las-palmas"  [[-15.41 28.1] [-15.33 27.95] [-15.5 27.62] [-16.0 27.55]]
   "port-sc-la-palma" [[-17.76 28.68] [-17.7 28.55] [-17.85 28.36] [-18.1 28.2]]})

(def antilles [[-22.0 25.0] [-35.0 21.5] [-50.0 19.6] [-61.0 19.3]])

(def legs
  "Ocean legs from open sea to each arrival port."
  {"port-la-habana"    (concat antilles [[-66.5 19.5] [-71.0 20.45] [-74.0 20.65] [-75.8 21.55]
                                         [-77.6 22.35] [-80.8 23.45] [-82.35 23.14]])
   "port-san-juan"     (concat antilles [[-64.5 18.9] [-66.1 18.48]])
   "port-la-guaira"    [[-22.0 24.0] [-35.0 19.0] [-50.0 15.0] [-61.1 13.6] [-64.0 11.6] [-66.93 10.62]]
   "port-limon"        [[-22.0 24.0] [-35.0 19.0] [-50.0 15.0] [-61.1 13.6] [-70.0 14.2] [-78.0 12.0] [-83.03 9.99]]
   "port-veracruz"     (concat antilles [[-66.5 19.5] [-71.0 20.45] [-74.0 20.65] [-75.8 21.55]
                                         [-77.6 22.35] [-80.8 23.45] [-84.5 23.4] [-90.0 22.4] [-96.13 19.17]])
   "port-montevideo"   [[-19.5 25.0] [-24.0 15.0] [-27.0 4.0] [-31.0 -6.0] [-35.0 -18.0] [-42.0 -26.0]
                        [-50.0 -32.0] [-55.3 -35.2] [-56.21 -34.91]]
   "port-buenos-aires" [[-19.5 25.0] [-24.0 15.0] [-27.0 4.0] [-31.0 -6.0] [-35.0 -18.0] [-42.0 -26.0]
                        [-50.0 -32.0] [-55.3 -35.2] [-57.6 -34.75] [-58.38 -34.6]]
   "port-rio"          [[-19.5 25.0] [-24.0 15.0] [-27.0 4.0] [-31.0 -6.0] [-36.5 -15.0] [-40.5 -21.8] [-43.17 -22.9]]
   "port-mogador"      [[-15.0 29.5] [-11.5 30.8] [-9.76 31.51]]})

(defn- catmull-rom
  "Smooth a polyline (uniform Catmull-Rom), `k` samples per segment."
  [pts k]
  (let [pts (vec pts)
        n (count pts)
        p (fn [i] (pts (max 0 (min (dec n) i))))]
    (conj
     (vec (for [i (range (dec n))
                s (range k)
                :let [t (/ s k)
                      [x0 y0] (p (dec i)) [x1 y1] (p i) [x2 y2] (p (inc i)) [x3 y3] (p (+ i 2))
                      t2 (* t t) t3 (* t2 t)
                      f (fn [a b c d] (* 0.5 (+ (* 2 b) (* (- c a) t)
                                                (* (+ (* 2 a) (* -5 b) (* 4 c) (- d)) t2)
                                                (* (+ (- a) (* 3 b) (* -3 c) d) t3))))]]
            [(f x0 x1 x2 x3) (f y0 y1 y2 y3)]))
     (peek pts))))

(defn- seg-len [[x0 y0] [x1 y1]]
  (let [kx (js/Math.cos (* (/ (+ y0 y1) 2) (/ js/Math.PI 180)))]
    (js/Math.hypot (* kx (- x1 x0)) (- y1 y0))))

(defn route
  "{:path [[lon lat] …] :frac [0 … 1]} or nil when no route is known."
  [from to]
  (when-let [leg (get legs to)]
    (let [path (catmull-rom (concat (get port-exit from [[-16.25 28.46]]) leg) 8)
          cum (reductions + 0 (map seg-len path (rest path)))
          total (last cum)]
      {:path path :frac (mapv #(/ % total) cum)})))

(def route-cache (memoize route))

(defn position
  "Point at fraction f ∈ [0,1] along a route, plus heading in degrees."
  [{:keys [path frac]} f]
  (let [n (count path)
        i (loop [lo 0 hi (dec n)]                  ; last index with frac ≤ f
            (if (<= (- hi lo) 1) lo
                (let [m (quot (+ lo hi) 2)] (if (<= (frac m) f) (recur m hi) (recur lo m)))))
        j (min (inc i) (dec n))
        [x0 y0] (path i) [x1 y1] (path j)
        span (- (frac j) (frac i))
        u (if (pos? span) (/ (- f (frac i)) span) 0)]
    [[(+ x0 (* u (- x1 x0))) (+ y0 (* u (- y1 y0)))]
     (* (/ 180 js/Math.PI) (js/Math.atan2 (- y1 y0) (- x1 x0)))]))
