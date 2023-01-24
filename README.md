# Archura Router

Uses Spring boot and Java HTTP client and virtual threads.

# Installation

The following steps explain, build, compile to native, create docker image, and run load test.

```shell
# use Graalvm Java 19 version
sdk install java 22.3.r19-grl

# install native image command line tool
gu install native-image

# build project
mvn clean install

# create native image 
./mvnw native:compile -Pnative

# build docker image
docker build -t archura-router:0.0.1 .

# run docker image
docker run --rm -it --memory="32MB" --cpus="0.5" -p 8080:8080 --name archura-router archura-router:0.0.1

# you can follow the CPU and Memory usage of the container
docker stats
```