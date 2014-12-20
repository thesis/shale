FROM dockerfile/java
MAINTAINER Matt Luongo (@mhluongo)

RUN sudo apt-get update
ADD target/shale-0.2.0-SNAPSHOT-standalone.jar /srv/shale.jar

EXPOSE 5000
CMD ["java", "-jar", "/srv/shale.jar"]
