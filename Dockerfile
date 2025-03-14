FROM maven:3-eclipse-temurin-17 as builder

WORKDIR /app

COPY pom.xml .
COPY assembly.xml .

RUN mvn dependency:resolve dependency:resolve-plugins

COPY src ./src

# Extract version dynamically from pom.xml and compile
RUN mvn help:evaluate -Dexpression=project.version -q -DforceStdout > version.txt && \
    mvn clean package

FROM debian:bookworm-slim as runner

RUN apt-get update && \
    apt-get install -y openjdk-17-jre avahi-daemon && \
    rm -rf /var/lib/apt/lists/*

# disable d-bus
RUN sed -i 's/.*enable-dbus=.*/enable-dbus=no/' /etc/avahi/avahi-daemon.conf
RUN sed -i 's/#allow-interfaces=eth0/allow-interfaces=eth0/' /etc/avahi/avahi-daemon.conf

# Copy the version file and package dynamically
COPY --from=builder /app/version.txt /version.txt
COPY --from=builder /app/target /opt/target

#
# Install uni-meter
#
RUN VERSION=$(cat /version.txt) && \
    tar -xzf /opt/target/uni-meter-${VERSION}.tgz -C /opt && \
    ln -s /opt/uni-meter-${VERSION} /opt/uni-meter && \
    cp /opt/uni-meter/config/uni-meter.conf /etc && \
    rm -r /version.txt && \
    rm -r /opt/target

ENTRYPOINT ["/opt/uni-meter/bin/uni-meter-and-avahi.sh"]
