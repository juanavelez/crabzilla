[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.github.crabzilla/crabzilla/badge.svg)](http://search.maven.org/#artifactdetails%7Cio.github.crabzilla%7Ccrabzilla%7C0.0.5%7C)
[![Build Status](https://travis-ci.org/crabzilla/crabzilla.svg?branch=master)](https://travis-ci.org/crabzilla/crabzilla)

# crabzilla

* [Overview](https://crabzilla.github.io/crabzilla/docs/overview.html)
* [example-manual](https://crabzilla.github.io/crabzilla/docs/example-manual.html)
* [vavr-example-manual](https://crabzilla.github.io/crabzilla/docs/vavr-example-manual.html)
* [kotlin-example-manual](https://crabzilla.github.io/crabzilla/docs/kotlin-example-manual.html)
* [Architecture decision records](https://github.com/crabzilla/crabzilla/tree/master/doc/architecture/decisions)

## Maven

Your core domain module must import:

```xml
<dependency>
  <groupId>io.github.crabzilla</groupId>
  <artifactId>crabzilla-core</artifactId>
  <version>0.0.5</version>
</dependency>
```

and your service module must import:

```xml
<dependency>
  <groupId>io.github.crabzilla</groupId>
  <artifactId>crabzilla-vertx</artifactId>
  <version>0.0.5</version>
</dependency>
```

## Archetypes

* TODO

## How to run the example

### Requirements

* Java 8
* Maven
* Docker compose
* Lombok plugin for your IDE
* Kotlin plugin for your IDE
1. Clone Crabzilla and build it running unit tests but skipping integration tests:

### Steps

```bash
git clone https://github.com/crabzilla/crabzilla
cd crabzilla
mvn clean install -DskipITs=true
```

2. Start a MySql instance. You can use docker-compose:

```bash
docker-compose up
```

3. Create the database schema using [Flyway](https://flywaydb.org/):

```bash
cd crabzilla-example1/crabzilla-example1-database
mvn compile flyway:migrate
```

4. Now you can run integration tests against database, skipping the unit tests:

```bash
# go back to crabzilla root
cd ../..
mvn verify -DskipUTs=true 
```

5. Now you finally can run the current [example](crabzilla-example1/crabzilla-example1-service/src/main/java/io/github/crabzilla/example1/Example1Launcher.java):

```bash
java -jar crabzilla-example1/crabzilla-example1-service/target/crabzilla-example1-service-0.0.5-fat.jar
```

## Wiki 

You can find more info on [wiki](https://github.com/crabzilla/crabzilla/wiki)
