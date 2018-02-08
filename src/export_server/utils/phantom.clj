(ns export-server.utils.phantom
  (:import (org.openqa.selenium.phantomjs PhantomJSDriver PhantomJSDriverService)
           (org.openqa.selenium.remote DesiredCapabilities)
           (org.openqa.selenium Dimension)
           (java.util ArrayList))
  (:require [clj-webdriver.core :as core]
            [clj-webdriver.taxi :refer :all :as taxi]
            [clj-webdriver.driver :refer [init-driver]]
            [clojure.java.io :as io :refer [output-stream]]
            [export-server.utils.rasterizator :as rasterizator]
            [taoensso.timbre :as timbre]))

;====================================================================================
; PhantomJS initialization
;====================================================================================
(defn- create-driver []
  (let [caps (DesiredCapabilities.)
        cliArgsCap (ArrayList.)]
    (.add cliArgsCap "--web-security=false")
    (.add cliArgsCap "--ssl-protocol=any")
    (.add cliArgsCap "--ignore-ssl-errors=true")
    (.setCapability caps PhantomJSDriverService/PHANTOMJS_CLI_ARGS cliArgsCap)
    (init-driver {:webdriver (PhantomJSDriver. caps)})))

(defonce drivers (atom []))
(defonce drivers-queue nil)

(defn- get-free-driver []
  (.poll drivers-queue))

(defn- return-driver [driver]
  (.add drivers-queue driver))

(defn setup-phantom []
  (reset! drivers [(create-driver) (create-driver) (create-driver) (create-driver)])
  (alter-var-root (var drivers-queue)
                  (fn [_]
                    (let [queue (java.util.concurrent.ConcurrentLinkedQueue.)]
                      (doseq [driver @drivers]
                        (.add queue driver))
                      queue))))

(defn stop-phantom []
  (doseq [driver @drivers]
    (try
      (quit driver)
      (catch Exception e nil))))

(defn exit [driver status msg]
  (quit driver)
  (println msg)
  (System/exit status))


;=======================================================================================================================
; Script --> SVG | PNG
;=======================================================================================================================
(def anychart-load-script "var args = arguments;(function(d) {var js, id = 'anychart', ref = d.getElementsByTagName('head')[0];if (d.getElementById(id)) {return;}js = d.createElement('script');js.id = id;js.src = args[0];ref.appendChild(js);}(document));")
(def anychart-script-path (str (io/resource "js/anychart-bundle.min.js")))
(def anychart-binary (slurp (io/resource "js/anychart-bundle.min.js")))
(def replacesvgsize (slurp (io/resource "js/replacesvgsize.min.js")))

(defn- trim-svg-string [str]
  (let [left-trim-str (clojure.string/replace str #"^\"" "")
        right-trim-str (clojure.string/replace left-trim-str #"\"$" "")]
    right-trim-str))

(defn- exec-script-to-png [d script exit-on-error options type]
  (let [prev-handles (.getWindowHandles (:webdriver d))]
    (execute-script d "window.open(\"\")")
    (let [new-handles (.getWindowHandles (:webdriver d))
          new-handle (first (clojure.set/difference (set new-handles) (set prev-handles)))
          prev-handle (first prev-handles)]
      (.window (.switchTo (:webdriver d)) new-handle)
      ;(prn "prev handles: " prev-handles)
      ;(prn "Current: " (.getWindowHandle (:webdriver d)))
      (let [startup
            (try
              (execute-script d "document.getElementsByTagName(\"body\")[0].style.margin = 0;
                                 document.body.innerHTML = '<div id=\"' + arguments[0] + '\" style=\"width:' + arguments[1] + ';height:' + arguments[2] + ';\"></div>'"
                              [(:container-id options) (:container-width options) (:container-height options)])
              (catch Exception e (str "Failed to execute Startup Script\n" (.getMessage e))))
            binary
            (try
              (execute-script d anychart-binary)
              (catch Exception e (str "Failed to execute AnyChat Binary File\n" (.getMessage e))))
            script
            (try
              (execute-script d script)
              (catch Exception e (str "Failed to execute Script\n" (.getMessage e))))
            waiting
            (try
              (let [now (System/currentTimeMillis)]
                (loop []
                  (if (not-empty (elements d "svg"))
                    nil
                    (if (> (System/currentTimeMillis) (+ now 2000))
                      "error"
                      (do
                        (Thread/sleep 10)
                        (recur))))))
              (catch Exception e (str "Failed to wait for SVG\n" (.getMessage e))))
            resize
            (try
              (execute-script d replacesvgsize)
              (catch Exception e (str "Failed to execute ReplaceSvgSize\n" (.getMessage e))))
            svg
            (try
              (html d (first (elements d "svg")))
              (catch Exception e (str "Failed to take SVG Structure\n" (.getMessage e))))
            screenshot (take-screenshot d :bytes nil)
            shoutdown
            (try
              (execute-script d "while (document.body.hasChildNodes()){document.body.removeChild(document.body.lastChild);}", [])
              (catch Exception e (str "Failed to execute Shoutdown Script\n" (.getMessage e))))
            error (some #(when (not (nil? %)) %) [startup binary script shoutdown waiting resize])]
        (execute-script d "window.close(\"\")")
        (.window (.switchTo (:webdriver d)) prev-handle)
        ;(prn "End handles: " (.getWindowHandles (:webdriver d)))
        ;(with-open [out (output-stream (clojure.java.io/file "/media/ssd/sibental/export-server-data/script-to-png.png"))]
        ;  (.write out screenshot))

        (if error
          (if exit-on-error
            (exit d 1 error)
            {:ok false :result error})
          (case type
            :png {:ok true :result screenshot}
            :svg {:ok true :result svg}))))))

(defn script-to-png [script quit-ph exit-on-error options type]
  (if-let [driver (if quit-ph (create-driver) (get-free-driver))]
    (let [svg (exec-script-to-png driver script exit-on-error options type)]
      (if quit-ph (quit driver) (return-driver driver))
      svg)
    {:ok false :result "Driver isn't available\n"}))


;=======================================================================================================================
; SVG --> PNG
;=======================================================================================================================
(defn- exec-svg-to-png [d svg exit-on-error width height]
  (let [prev-handles (.getWindowHandles (:webdriver d))]
    (execute-script d "window.open(\"\")")
    (let [new-handles (.getWindowHandles (:webdriver d))
          new-handle (first (clojure.set/difference (set new-handles) (set prev-handles)))
          prev-handle (first prev-handles)]
      (.window (.switchTo (:webdriver d)) new-handle)
      (when (and width height)
        (.setSize (.window (.manage (:webdriver d))) (Dimension. width height)))
      (let [startup
            (try
              (execute-script d "document.body.style.margin = 0;
                                 document.body.innerHTML = arguments[0]"
                              [svg width height])
              (catch Exception e (str "Failed to execute Startup Script\n" (.getMessage e))))
            screenshot (take-screenshot d :bytes nil)
            shoutdown
            (try
              (execute-script d "while (document.body.hasChildNodes()){document.body.removeChild(document.body.lastChild);}", [])
              (catch Exception e (str "Failed to execute Shoutdown Script\n" (.getMessage e))))
            error (some #(when (not (nil? %)) %) [startup shoutdown])]
        (execute-script d "window.close(\"\")")
        (.window (.switchTo (:webdriver d)) prev-handle)
        ;(with-open [out (output-stream (clojure.java.io/file "/media/ssd/sibental/export-server-data/script-to-png.png"))]
        ;  (.write out screenshot))
        (if error
          (if exit-on-error
            (exit d 1 error)
            {:ok false :result error})
          {:ok true :result screenshot})))))

(defn svg-to-png [svg quit-ph exit-on-error width height]
  (if-let [driver (if quit-ph (create-driver) (get-free-driver))]
    (let [svg (rasterizator/clear-svg svg)
          png-result (exec-svg-to-png driver svg exit-on-error width height)]
      (if quit-ph (quit driver) (return-driver driver))
      png-result)
    {:ok false :result "Driver isn't available\n"}))


;=======================================================================================================================
; HTML --> PNG
;=======================================================================================================================
(defn exec-html-to-png [d file exit-on-error width height svg-type?]
  (let [prev-handles (.getWindowHandles (:webdriver d))]
    (execute-script d "window.open(\"\")")
    (let [new-handles (.getWindowHandles (:webdriver d))
          new-handle (first (clojure.set/difference (set new-handles) (set prev-handles)))
          prev-handle (first prev-handles)]
      (.window (.switchTo (:webdriver d)) new-handle)
      (when (and width height)
        (.setSize (.window (.manage (:webdriver d))) (Dimension. width height)))

      (timbre/info "Open file:" file)
      (let [startup (.get (:webdriver d) file)

            waiting
            (try
              (let [now (System/currentTimeMillis)]
                (loop []
                  (if (not-empty (elements d "svg"))
                    nil
                    (if (> (System/currentTimeMillis) (+ now 4000))
                      "error"
                      (do
                        (Thread/sleep 10)
                        (recur))))))
              (catch Exception e (str "Failed to wait for SVG\n" (.getMessage e))))

            svg
            (try
              (taxi/html d (first (elements d "svg")))
              (catch Exception e (str "Failed to take SVG Structure\n" (.getMessage e))))

            screenshot (take-screenshot d :bytes nil)

            shutdown
            (try
              (execute-script d "while (document.body.hasChildNodes()){document.body.removeChild(document.body.lastChild);}", [])
              (catch Exception e (str "Failed to execute Shoutdown Script\n" (.getMessage e))))

            error (some #(when (not (nil? %)) %) [startup shutdown])]

        (execute-script d "window.close(\"\")")
        (.window (.switchTo (:webdriver d)) prev-handle)

        ;(with-open [out (output-stream (clojure.java.io/file "/media/ssd/sibental/export-server-data/html-to-png.png"))]
        ;  (.write out screenshot))

        (if error
          (if exit-on-error
            (exit d 1 error)
            {:ok false :result error})
          {:ok true :result (if svg-type? svg screenshot)})))))


(defn html-to-png [file quit-ph exit-on-error width height & [svg-type?]]
  (if-let [driver (if quit-ph (create-driver) (get-free-driver))]
    (let [png-result (exec-html-to-png driver file exit-on-error width height svg-type?)]
      (if quit-ph (quit driver) (return-driver driver))
      png-result)
    {:ok false :result "Driver isn't available\n"}))