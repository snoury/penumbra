;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns examples.convolution
  (:use [penumbra opengl compute window]))

(defn draw-rect [x y w h]
  (with-disabled :texture-rectangle
    (draw-quads
     (vertex x y)
     (vertex (+ x w) y)
     (vertex (+ x w) (+ y h))
     (vertex x (+ y h)))))

(defn reset-image [tex w h]
  (render-to-texture tex
    (with-projection (ortho-view 0 2 2 0 -1 1)
      (dotimes [_ 100]
        (apply color (take 3 (repeatedly rand)))
        (apply draw-rect (take 4 (repeatedly rand))))))
  tex)

(defn init [state]

  (defmap blur
    (let [value (float4 0.0)
          sum 0.0]
      (convolution %2
        (+= sum %2)
        (+= value (* %2 %1)))
      (/ value sum))) 

  (def kernel
    (wrap (map float
               [0.01 0.01 1
                0.01 1    0.01
                1    0.01 0.01])))
  
  (enable :texture-rectangle)
  (ortho-view 0 2 2 0 -1 1)
  (assoc state
    :tex (create-byte-texture 256 256)))

(defn key-press [key state]
  (let [tex (:tex state)]
    (cond
      (= key " ")
      (enqueue #(assoc %
                  :tex (reset-image tex 256 256)))
      (= key :enter)
      (enqueue #(assoc %
                  :tex (with-frame-buffer
                         (first (blur [tex [kernel]])))))))
  state)

(defn display [_ state]
  (blit (:tex state)))

(start {:display display, :key-press key-press, :init init} {})
