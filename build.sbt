import AssemblyKeys._ 

name := "Gobbler"

assemblySettings

version := "0.01"

scalaVersion := "2.9.1"

libraryDependencies += "org.apache.httpcomponents" % "httpclient" % "4.2.1"

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"
 
libraryDependencies += "com.typesafe.akka" % "akka-actor" % "2.0.2"

libraryDependencies += "com.google.guava" % "guava" % "13.0.1"

