(ns newark.emitter
  (:require [clojure.string :as str]
            [newark.constants :as constants]))

(defn wrap [params args code]
  (str "(function(" (str/join "," params)
       ") {\n\n" code "\n})("
       (str/join "," args) ");"))

(defn wrap-toplevel [code]
  (wrap [constants/GLOBAL]
        ["this"]
        (str constants/PRELUDE code)))

(def accumulator (StringBuilder.))
(defn write! [x] (.append accumulator x))
(defn clear! []  (.setLength accumulator 0))

(def *tab-width* 2)
(def depth (atom 0))
(defn reset-depth! [] (reset! depth 0))
(defn indent! []
  (swap! depth + *tab-width*))
(defn unindent! []
  (swap! depth - *tab-width*))

(defn CL [] (write! ":"))
(defn SC [] (write! ";"))               ;
(defn NL [] (write! "\n"))
(defn SP [] (write! " "))
(defn OP [] (write! "("))
(defn CP [] (write! ")"))
(defn OB [] (write! "["))
(defn CB [] (write! "]"))
(defn OC [] (write! "{"))
(defn CC [] (write! "}"))
(defn CM [] (write! ","))
(defn TAB [] (dotimes [_ @depth] (SP)))

(defmacro in-braces [& body]
  `(do (OC) (indent!) ~@body (unindent!) (NL) (TAB) (CC)))
(defmacro in-brackets [& body]
  `(do (OB) ~@body (CB)))
(defmacro in-parens [& body]
  `(do (OP) ~@body (CP)))

(declare emit)

(defn separated-by [sep tokens]
  (when (seq tokens)
    (emit (first tokens))
    (doseq [token (rest tokens)] (write! sep) (emit token))))

(defn commas [tokens] (separated-by ", " tokens))
(defn comma-list [xs] (in-parens (commas xs)))
(defn emit-regex [s] (write! "/") (write! s) (write! "/"))

(defn emit-body [tokens]
  (if (empty? tokens)
    (write! "{;}")
    (in-braces (doseq [t tokens]
                 (NL)
                 (TAB)
                 (emit t)
                 (when-not (= :COMMENT (first t))
                   (SC))))))

(defn emit-body* [tokens]
  (doseq [t tokens]
    (emit t) (SC) (NL)))

(defn if-token? [t] (= :IF (first t)))

(defn emit-if [test then else]
  (write! "if ")
  (in-parens (emit test))
  (SP)
  (emit-body then)
  (when else
    (write! " else ")    
    (if (and (if-token? (first else))
             (= 1 (count else)))
      (emit else)      
      (emit-body else))))

(defn emit-operator [opsym tokens]
  (case (count tokens)
    0 (throw (Exception. (str "empty arglist for operator " opsym)))
    1 (in-parens (write! opsym) (SP) (emit (first tokens)))
    (in-parens (separated-by (str " " opsym " ") tokens))))

(defn emit-literal [x]
  (cond
   (nil? x)    (write! "null")
   (symbol? x) (write! (name x))
   (string? x) (write! (pr-str x))
   :else       (write! x)))

(defn emit-try-catch [t v c & [f]]
  (write! "try ")
  (emit-body t)
  (write! " catch (")
  (emit v)
  (write! ") ")
  (emit-body c)
  (when f
    (write! " finally ")
    (emit-body f)))

(defn emit [[tag a b c d :as token]]
  (case tag
    :CONSTANT (emit-literal a)
    :RAW      (write! a)
    :REGEX    (do (write! "/") (emit a) (write! "/"))
    :ARRAY    (in-brackets (commas a))
    :PROJECT  (do (emit a) (in-brackets (emit b)))
    :CALL     (do (emit a) (comma-list b))
    :FN       (in-parens
               (write! "function ")
               (comma-list a)
               (SP)
               (emit-body b))
    :WHILE    (do (write! "while ")
                  (in-parens (emit a))
                  (SP)
                  (emit-body b))
    :IF       (emit-if a b c)
    :SET!     (do (emit a) (write! " = ") (emit b))
    :OPCALL   (emit-operator a b) 
    :BREAK    (write! "break")
    :RETURN   (do (write! "return ") (emit a))
    :DECLARE  (do (write! "var ") (commas a))
    :NEW      (do (write! "new ") (emit a))
    :TRY      (emit-try-catch a b c)
    :TRY*     (emit-try-catch a b c d)
    :THROW    (do (write! "throw ")
                  (emit a))
    :COMMENT  (do (write! "\n/* ")
                  (write! a)
                  (write! " */\n"))
    :FOR_EACH_PROPERTY
    (do (write! "for ")
        (in-parens (emit a) (write! " in ") (emit b))
        (SP)
        (emit-body c))
    :VAR
    (case a
      :GLOBAL (write! (str constants/USER "['" d "$" c "']"))
      :LOCAL  (write! (str "v" b))
      :AUTO   (write! (str "_" b)))))

(defn emit-tokens [tokens]
  (clear!)
  (reset-depth!)
  (doseq [t tokens] (emit t))
  (str accumulator))

(defn emit-tokens* [tokens]
  (clear!)
  (reset-depth!)
  (emit-body* tokens)
  (str accumulator))