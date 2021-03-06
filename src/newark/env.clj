(ns newark.env
  (:refer-clojure :exclude [resolve])
  (:require [clojure.string :as str]
            [newark.constants :as constants]
            [newark.util :as util]))

(declare make-env import! core)

(defn load-package [id]
  (throw (RuntimeException. "load-package not configured")))

(def next-env-id (util/make-generator))
(def tag-db (atom {}))
(def modules (atom {}))

(defn str* [x]
  (if (keyword? x) (name x) (str x)))

(defn find-or-create-module [ns]
  (let [ns (str* ns)]
    (or (get @modules ns)
        (let [e (make-env ns)]
          (swap! modules assoc ns e)
          e))))

(defn find-module [ns]
  (or (get @modules (str* ns))
      (throw (RuntimeException.
              (str "no module named " ns " exists")))))

(defn next-tag-id [env]
  (let [env-id (:id env)
        tag-id (or (get @tag-db env-id) 0)]
    (swap! tag-db assoc env-id (inc tag-id))
    tag-id))

(defn make-tag [env]
  (let [env-id (:id env)
        tag-id (next-tag-id env)]
    {:env env
     :tag (str env-id "_" tag-id)}))

(defn symbol->tags [s]
  (:tags (meta s)))

(defn symbol->tag-string [s]
  (str/join ":" (map :tag (symbol->tags s))))

(defn tagged? [s]
  (not (empty? (symbol->tags s))))

(defn symbol->key [s]
  (cond
   (nil? s)
   nil
   
   (tagged? s)
   (str "#[" (symbol->tag-string s) "]" (name s))
   
   :else
   (name s)))

(defn tag-symbol [s t]
  (if (namespace s)
    s
    (let [ts (symbol->tags s)]
      (if (= t (first ts))
        (util/merge-meta s {:tags (rest ts)})
        (util/merge-meta s {:tags (cons t ts)})))))

(defn ensure-tag [s t]
  (if (namespace s)
    s
    (let [ts (symbol->tags s)]
      (if (= t (first ts))
        s
        (util/merge-meta s {:tags (cons t ts)})))))

(defn remove-tag [s t]
  (if (namespace s)
    s
    (let [ts (symbol->tags s)]
      (if (= t (first ts))
        (util/merge-meta s {:tags (rest ts)})
        s))))

(defn untag-symbol [s]
  (util/merge-meta s {:tags (rest (symbol->tags s))}))

(defn sanitize [x t]
  (cond
   (symbol? x)
   (tag-symbol x t)

   (seq? x)
   (map #(sanitize % t) x)

   (vector? x)
   (vec (map #(sanitize % t) x))

   :else
   x))

(defn make-env [module & [symbols]]
  {:module            module
   :symbols           (or symbols (util/make-dictionary))
   :id                (next-env-id)   
   :imported-packages (atom #{})
   :imported-symbols  (atom #{})})

(defn make-standard-env [id]
  (let [e (make-env (util/name* id))]
    (import! e core)
    e))

(defn extend-env [env]
  (assoc env
    :symbols (util/extend-dictionary (:symbols env))
    :id      (next-env-id)))

(defn reify-symbol [s]
  (if (tagged? s)
    (symbol (str "#:" (symbol->tag-string s) ":" (name s)))
    s))

(defn bind-macro [e s m]
  (util/dictionary-set (:symbols e) (symbol->key s) m))

(defn bind-symbol [e s]
  (let [s* (reify-symbol s)]
    (util/dictionary-set (:symbols e) (symbol->key s) s*)
    s*))

(defn bind-label [e s]
  (let [s* (reify-symbol s)]
    (util/dictionary-set (:symbols e) [:LABEL (symbol->key s)] s*)
    s*))

(defn bind-global [e s]
  (let [s1 (reify-symbol s)
        s2 (symbol (:module e) (name s1))]    
    (util/dictionary-set* (:symbols e) (symbol->key s1) s2)
    s2))

(defn resolve-label [e s]
  (util/dictionary-get (:symbols e) [:LABEL (symbol->key s)]))

(defn resolve-symbol [e s]
  (when (symbol? s)
    (or
     (when-let [ns (namespace s)]       
       (resolve-symbol (find-module ns)
                       (with-meta (symbol nil (name s)) (meta s))))
     (util/dictionary-get (:symbols e) (symbol->key s))
     (when (tagged? s)
       (recur (:env (first (symbol->tags s)))
              (untag-symbol s))))))

(def core (find-or-create-module "core"))

(doseq [x ["import"
           "include"
           "define*"
           "define-syntax"
           "define-symbol-syntax"
           "let-syntax"
           "let-symbol-syntax"
           "if"
           "set!"
           "let"
           "letrec"
           "."
           "fn"
           "method"
           "block"
           "loop*"
           "return-from"
           "begin"
           "throw"
           "unwind-protect"
           "do-properties*"
           "quasiquote"
           "unquote"
           "unquote-splicing"
           "raw"
           "quote"
           "js"
           "new"

           "newark"
           "require"]]
  (bind-global core (symbol x)))

(defn import! [e1 e2 & [pred]]
  (let [pred (or pred (constantly true))]
    (doseq [[k v] @(first (:symbols e2))]
      (util/dictionary-set* (:symbols e1) k v)
      (swap! (:imported-symbols e1) conj k))))

