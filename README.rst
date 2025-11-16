Jenkins LSP
==========

Building
--------

Requirements:

* Java 8+
* Maven 3.x

To build the shaded, executable JAR:

.. code-block:: bash

   mvn -q -DskipTests=true package

The resulting file will be:

.. code-block:: text

   target/jenkins-lsp-1.0.0-all.jar

Running
-------

The server speaks JSON-RPC 2.0 over stdio, as expected by most LSP
clients. A typical launch command from an editor configuration looks
like:

.. code-block:: bash

   java -jar target/jenkins-lsp-1.0.0-all.jar
