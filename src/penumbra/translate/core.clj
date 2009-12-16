;   Copyright (c) Zachary Tellman. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns penumbra.translate.core
  (:use [clojure.contrib.pprint])
  (:use [clojure.contrib.def :only (defmacro- defvar defvar-)])
  (:import (java.text ParseException))
  (:import (java.io StringWriter))
  (:require [clojure.zip :as zip]))

;;;

(defvar *generator* nil
  "Anything returned by this is prepended to the beginning of the expression.
  Currently only used for imports, could also be used for anonymous functions.")
(defvar *parser* nil
  "Returns a string in the native language for the given s-expression.")
(defvar *transformer* nil
  "Macros, applied from leaf to root across entire expression.")
(defvar *inspector* nil
  "Returns the type of the expression.  Applied as :tag metadata.")
(defvar *tagger* nil
  "Specialized macro.  Should set :assignment and :defines tags.")

;;;

(defn id [x]
  (cond
    (string? x) (symbol x)
    (symbol? x) (symbol (name x))
    (keyword? x) (symbol (name x))
    :else nil))

(defn meta? [x]
  (instance? clojure.lang.IMeta x))

(defn typeof [x]
  (cond
    (integer? x) :int
    (float? x) :float
    (keyword? x) (when *inspector* (*inspector* x))
    (not (meta? x)) nil
    (:numeric-value (meta x)) (typeof (:numeric-value (meta x)))
    :else (:tag (meta x))))
                                       
(defn add-meta [x & metadata]
  (if (meta? x)
    (with-meta x (apply assoc (list* (meta x) metadata)))
    x))

(defn seq-wrap [s]
  (if (sequential? s) s (list s)))

(defn realize [s]
  (if (seq? s) (doall s) s))

(defn- str-pprint [x]
  (let [s (new StringWriter)]
    (pprint x s)
    (str s)))

(defn indent
  "Indents every line two spaces."
  [s]
  (let [lines (seq (.split s "\n"))]
    (str (apply str (interpose "\n" (map #(str "  " %) lines))) "\n")))

;;;

(defn mimic-expr [a b]
  (if (meta? b)
    (with-meta
      (cond
        (vector? a) (vec b)
        (map? a) (hash-map b)
        :else b)
      (merge ^a ^b))
    b))

(defn tree-filter [f x]
  (filter f (tree-seq sequential? seq x)))

(defn- do-tree* [f x depth]
  (cond
    (not (sequential? x))
      (f x depth)
    :else
      (do
        (f x depth)
        (doseq [i x] (do-tree* f i (inc depth))))))
            
(defn- do-tree [f x]
  (do-tree* f x 0))
  
(defn tree-map
  "A post-order recursion, so that we can build transforms upwards."
  [f x]
  (cond
    (not (meta? x))
      (or (f x) x)
    (not (sequential? x))
      (let [x* (or (f x) x)]
        (with-meta x* (merge ^x ^x*)))
    (empty? x)
      ()
    :else
      (let [x* (mimic-expr x (realize (map #(tree-map f %) x)))]
        (mimic-expr x* (or (f x*) x*)))))

(defn tree-map* [f x]
  (loop [x (tree-map f x)]
    (let [x* (tree-map f x)]
      (if (= x x*) x (recur x*)))))

(defn print-tree [x]
  (println
   (with-out-str
     (do-tree
      #(print
        (apply str (realize (take (* 2 %2) (repeat "  "))))
        (realize %) "|" (typeof %) "|" (meta (realize %)) "\n")
      x))))

;;;

(defmacro- defn-try [name f message]
  `(defn ~name [x#]
    (try
      (~f x#)
      (catch ParseException pe#
        (throw pe#))
      (catch Exception e#
        (throw (ParseException. (str "\n" ~message "\n" (-> x# print-tree with-out-str) (.getMessage e#)) 0))))))

(defmacro- defn-try-
  [name f message]
  (list `defn-try (add-meta name :private true) f message))

;;;

(defn-try- try-transform
  #(realize (*transformer* %))
  "Error while transforming")

(defn- transform-div [x]
  (tree-map #(if (and (symbol? %) (= "/" (name %))) 'div) x))

(defn- transform-expr* [x]
  (if *transformer*
    (->> x transform-div (tree-map try-transform))
    x))

;;;

(defn-try try-generate
  #(realize (*generator* %))
  "Error while generating")

(defn generate-exprs [x]
  (loop [body (list x)
         tail (-> x try-generate transform-expr*)]
    (if (empty? tail)
     body
     (recur
       (concat body (if (seq? (ffirst tail)) (apply concat tail) tail))
       (-> tail try-generate transform-expr*)))))

;;;

(defn-try try-inspect
  #(realize
    (if (meta? %)
      (add-meta % :tag (or (:tag ^%) (*inspector* %)))
      %))
  "Error while inferring type")

(defn inspect-exprs [x]
  (if *inspector*
    (tree-map try-inspect x)
    x))

;;;

(defn- scope? [x]
  (and (meta? x) (:scope ^x)))

(defn- let? [x]
  (and (meta? x) (:let ^x)))

(defvar- *vars* nil)
(defvar- *let* false)

(defn scope-map
  [f x]
  (binding [*vars* (if (scope? x) (atom @*vars*) *vars*)
            *let*  (if (let? x) true false)]
    (cond
      (not (meta? x))
      (or (f x) x)
      (not (sequential? x))
      (let [x* (or (f x) x)]
        (with-meta x* (merge ^x ^x*)))
      (empty? x)
      ()
      :else
      (let [x* (mimic-expr x (realize (map #(scope-map f %) x)))]
        (mimic-expr x* (or (f x*) x*))))))

(defn tag-first-appearance [x]
  (binding [*vars* (atom #{})]
    (->>
      x
      (scope-map
       (fn [x]
         (if (and (symbol? x) (:assignment ^x) (or *let* (not (@*vars* x))))
          (do
            (swap! *vars* #(conj % x))
            (add-meta x :first-appearance true))
          x))))))

(defn-try try-tag
  #(realize (*tagger* %))
  "Error while tagging")

(defn- tag-exprs [x]
  (if *tagger*
    (->> x (tree-map try-tag) tag-first-appearance)
    x))

;;;

(defn declared-vars [x]
  (distinct (tree-filter #(:assignment ^%) x)))

(defn typeof-var
  "Determine type, if possible, of var within x"
  [var x]
  (let [vars  (tree-filter #(or (= var %) (and (meta? %) (= var (:defines ^%)))) x)
        types (distinct (filter identity (map typeof vars)))]
    (cond
      (empty? types)
        nil
      (= 1 (count types))
        (first types)
      :else
        (throw (ParseException. (str "Ambiguous type for " var ", cannot decide between " (with-out-str (prn types) (pprint x))) 0)))))

(defn- tagged-vars [x]
  (let [vars (atom [])]
    (tree-map
      (fn [x]
        (if (:tag ^x)
          (swap! vars #(conj % x)))
        x)
      x)
    @vars))

(defn tag-var
  "Add :tag metadata to all instances of var in x"
  [var type x]
  (tree-map #(if (= var %) (add-meta % :tag type)) x))

(defn infer-types
  "Repeatedly applies inspect-exprs and tag-var until everything is typed"
  [x]
  (loop [x x, tagged (tagged-vars x), iterations 0]
    (let [vars          (declared-vars x)
          types         (zipmap vars (map #(typeof-var % x) vars))
          known-types   (filter (fn [[k v]] v) types)
          unknown-types (filter (fn [[k v]] (not v)) types)
          x*            (inspect-exprs (reduce (fn [x [k v]] (tag-var k v x)) x known-types))
          tagged*       (tagged-vars x*)]
      (cond
        (empty? unknown-types)
          x*
        (and (= (count tagged) (count tagged*)) (< 20 iterations)) ;TODO: determine max sexpr depth and use that instead
          (throw (Exception. (str "Unable to determine type of " (with-out-str (prn (keys unknown-types)) (print-tree x)))))
        :else
          (recur x* tagged* (inc iterations))))))
        
;;;

(defn parse-lines
  "Maps *parser* over a list of s-expressions, filters out empty lines,
  and optionally adds a terminating character."
  ([exprs] (parse-lines exprs ""))
  ([exprs termination]
     (if (and
          (seq? exprs)
          (= 1 (count exprs))
          (seq? (first exprs)))
      (parse-lines (first exprs) termination)
      (let [exprs             (if (seq? (first exprs)) exprs (list exprs))
            translated-exprs  (map #(*parser* %) exprs)
            filtered-exprs    (filter #(not= (.trim %) "") translated-exprs)
            terminated-exprs  (map #(if (.endsWith % "\n") % (str % termination "\n")) filtered-exprs)]
        (apply str terminated-exprs)))))

(defn-try parse
  #(realize (*parser* %))
  "Error while parsing")

;;;

(defn transform-expr
  [x]
  (->> x
    transform-expr*
    generate-exprs reverse
    tag-exprs
    infer-types))

(defn translate-expr
  [x]
  (parse-lines (transform-expr x)))


