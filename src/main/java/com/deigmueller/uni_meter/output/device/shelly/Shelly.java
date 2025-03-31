package com.deigmueller.uni_meter.output.device.shelly;

import com.deigmueller.uni_meter.application.UdpServer;
import com.deigmueller.uni_meter.application.UniMeter;
import com.deigmueller.uni_meter.application.WebsocketInput;
import com.deigmueller.uni_meter.application.WebsocketOutput;
import com.deigmueller.uni_meter.common.shelly.Rpc;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.deigmueller.uni_meter.output.ClientContext;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.typesafe.config.Config;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.http.javadsl.model.ws.*;
import org.apache.pekko.http.javadsl.server.Route;
import org.apache.pekko.stream.UniqueKillSwitch;
import org.apache.pekko.stream.connectors.udp.Datagram;
import org.apache.pekko.stream.javadsl.*;
import org.apache.pekko.util.ByteString;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Getter(AccessLevel.PROTECTED)
@Setter(AccessLevel.PROTECTED)
public abstract class Shelly extends OutputDevice {
  // Instance members
  private final Instant startTime = Instant.now();
  private final String bindInterface = getConfig().getString("interface");
  private final int bindPort = getConfig().getInt("port");
  private final String defaultMac = getConfig().getString("device.mac");
  private final String defaultHostname = getConfig().getString("device.hostname");

  /**
   * Protected constructor
   * @param context Actor context
   * @param controller Uni-meter controller
   * @param config The output device configuration
   */
  protected Shelly(@NotNull ActorContext<Command> context,
                   @NotNull ActorRef<UniMeter.Command> controller,
                   @NotNull Config config) {
    super(context, controller, config, Shelly::initRemoteContexts);
  }

  /**
   * Create the actor's ReceiveBuilder
   * @return The actor's ReceiveBuilder
   */
  @Override
  public ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(ShellyGet.class, this::onShellyGet)
          .onMessage(SettingsGet.class, this::onSettingsGet)
          .onMessage(StatusGet.class, this::onStatusGet);
  }

  /**
   * Handle an HTTP GET request for the Shelly device information
   * @param request Request for the Shelly device information
   * @return Same behavior
   */
  abstract protected Behavior<Command> onShellyGet(ShellyGet request);

  /**
   * Handle an HTTP GET request for the Shelly device settings
   * @param request Request for the Shelly device settings
   * @return Same behavior
   */
  protected Behavior<Command> onSettingsGet(SettingsGet request) {
    logger.trace("Shelly.onSettingsGet()");

    request.replyTo().tell(
          new Settings(
                new Device(
                      getConfig().getString("device.type"),
                      getMac(request.remoteAddress()),
                      getHostname(request.remoteAddress()),
                      getNumOutputs(),
                      getNumMeters()),
                new Login(false, false, null),
                getConfig().getString("fw"),
                true));

    return Behaviors.same();
  }

  /**
   * Handle an HTTP GET request for the Shelly device status
   * @param request Request for the Shelly device status
   * @return Same behavior
   */
  protected Behavior<Command> onStatusGet(@NotNull StatusGet request) {
    logger.trace("Shelly.onStatusGet()");

    request.replyTo().tell(
          new Status(
                createWiFiStatus(),
                createCloudStatus(),
                createMqttStatus(),
                getTime(),
                Instant.now().getEpochSecond(),
                1,
                false,
                getMac(request.remoteAddress()),
                50648,
                38376,
                32968,
                233681,
                174194,
                getUptime(),
                28.08,
                false,
                createTempStatus()));

    return Behaviors.same();
  }
  
  /**
   * Process the specified RPC request
   * @param request Incoming RPC request to process
   * @param createTextResponse Flag indicating whether to create a text or binary response
   * @param output Actor reference to the websocket output actor
   */
  protected void processRpcRequest(@NotNull InetAddress remoteAddress,
                                   @NotNull Rpc.Request request,
                                   boolean createTextResponse,
                                   @NotNull ActorRef<WebsocketOutput.Command> output) {
    logger.trace("Shelly.processRpcRequest()");
    
    Rpc.ResponseFrame response = createRpcResponse(remoteAddress, request);

    Message wsResponse;
    if (createTextResponse) {
      wsResponse = TextMessage.create(Rpc.responseToString(response));
    } else {
      wsResponse = BinaryMessage.create(ByteString.fromArrayUnsafe(Rpc.responseToBytes(response)));
    }

    output.tell(new WebsocketOutput.Send(wsResponse));
  }

  protected abstract Rpc.ResponseFrame createRpcResponse(@NotNull InetAddress remoteAddress,
                                                         @NotNull Rpc.Request request);
  
  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
  
  protected String getTime() {
    return LocalTime.now().format(TIME_FORMATTER);
  }
  
  protected long getUptime() {
    return Duration.between(startTime, Instant.now()).getSeconds();
  }

  @Override
  protected Route createRoute() {
    return null;
  }

  @Override
  protected int getNumOutputs() {
    return 0;
  }

  @Override
  protected int getNumMeters() {
    return 0;
  }
  
  protected WiFiStatus createWiFiStatus() {
    return new WiFiStatus(getConfig().getConfig("wifi-status"));
  }

  protected CloudStatus createCloudStatus() {
    return new CloudStatus(getConfig().getConfig(("cloud-status")));
  }

  protected MqttStatus createMqttStatus() {
    return new MqttStatus(false);
  }

  protected TempStatus createTempStatus() {
    return new TempStatus(28.08, 82.54, true);
  }

  /**
   * Get the hostname to use for the specified remote address
   * @param remoteAddress Remote address
   * @return Hostname to use
   */
  protected String getHostname(@NotNull InetAddress remoteAddress) {
    ClientContext clientContext = getClientContexts().get(remoteAddress);
    if (clientContext instanceof ShellyClientContext shellyRemoteContext && shellyRemoteContext.hostname() != null) {
      return shellyRemoteContext.hostname();
    }
    
    return defaultHostname;
  }
  
  /**
   * Get the MAC address to use for the specified remote address
   * @param remoteAddress Remote address
   * @return MAC address to use
   */
  protected String getMac(@NotNull InetAddress remoteAddress) {
    ClientContext clientContext = getClientContexts().get(remoteAddress);
    if (clientContext instanceof ShellyClientContext shellyRemoteContext && shellyRemoteContext.mac() != null) {
      return shellyRemoteContext.mac();
    }

    return defaultMac;
  }

  /**
   * Initialize the remote contexts
   * @param logger Logger
   * @param remoteContexts List of remote context configuration
   * @param remoteContextMap Target Map of remote contexts
   */
  protected static void initRemoteContexts(@NotNull Logger logger,
                                           @NotNull List<? extends Config> remoteContexts,
                                           @NotNull Map<InetAddress, ClientContext> remoteContextMap) {
    for (Config remoteContext : remoteContexts) {
      try {
        String address = remoteContext.getString("address");
        String mac = remoteContext.hasPath("mac")
              ? remoteContext.getString("mac").toUpperCase()
              : null;
        String hostname = mac != null
              ? "shellypro3em-" + mac.toLowerCase()
              : null;
        
        Double powerFactor = remoteContext.hasPath("power-factor") 
              ? remoteContext.getDouble("power-factor")
              : null;
        
        remoteContextMap.put(
              InetAddress.getByName(address),
              new ShellyClientContext(mac, hostname, powerFactor));
      } catch (UnknownHostException ignore) {
        logger.debug("unknown host: {}", remoteContext.getString("address"));
      }
    }
  }
  
  
  public record ShellyGet(
        @NotNull InetAddress remoteAddress,
        @NotNull ActorRef<Rpc.GetDeviceInfoResponse> response
  ) implements Command {}
  
  public record HttpRpcRequest(
        @NotNull InetAddress remoteAddress,
        @NotNull Rpc.Request request,
        @NotNull ActorRef<Rpc.ResponseFrame> replyTo
  ) implements Command {}
  
  public record WebsocketOutputOpened(
        @NotNull String connectionId,
        @NotNull InetAddress remoteAddress,
        @NotNull ActorRef<WebsocketOutput.Command> sourceActor
  ) implements Command {}

  public record SettingsGet(
        @NotNull InetAddress remoteAddress,
        @NotNull ActorRef<Settings> replyTo
  ) implements Command {}
  
  public record WrappedWebsocketInputNotification(
        @NotNull WebsocketInput.Notification notification
  ) implements Command {}
  
  public record WrappedUdpServerNotification(
        @NotNull UdpServer.Notification notification
  ) implements Command {}
  
  public record WebsocketProcessPendingEmGetStatusRequest(
        @NotNull WebsocketContext websocketContext,
        @NotNull Message websocketMessage
  ) implements Command {}

  public record UdpClientProcessPendingEmGetStatusRequest(
        @NotNull UdpClientContext udpClientContext,
        @NotNull Datagram datagram
  ) implements Command {}

  public enum ThrottlingQueueClosed implements Command {
    INSTANCE
  }

  public record ShellyInfo(
        String type,
        String mac,
        boolean auth,
        String fw,
        boolean discoverable,
        int longid,
        int num_outputs,
        int num_meters
  ) {}

  public record Settings(
        Device device,
        Login login,
        String fw, 
        boolean discoverable
  ) {}

  public record Device(
      String type,
      String mac,
      String hostname,
      int num_outputs,
      int num_meters
  ) {}
  
  public record Login(
        boolean enabled,
        boolean unprotected,
        String username
  ) {}

  public record StatusGet(
        @NotNull InetAddress remoteAddress,
        @NotNull ActorRef<Status> replyTo
  ) implements Command {}

  @Getter
  @AllArgsConstructor
  public static class Status implements Rpc.Response{
    private final @NotNull WiFiStatus wifi_sta;
    private final @NotNull CloudStatus cloud;
    private final @NotNull MqttStatus mqtt;
    private final @NotNull String time;
    private final long unixtime;
    private final int serial;
    private final boolean has_update;
    private final String mac;
    private final long ram_total;
    private final long ram_free;
    private final long ram_lwm;
    private final long fs_size;
    private final long fs_free;
    private final long uptime;
    private final double temperature;
    private final boolean overtemperature;
    private final TempStatus tmp;
  }

  public record WiFiStatus(
        @JsonProperty("connected") boolean connected,
        @JsonProperty("ssid") String ssid,
        @JsonProperty("ip") String ip,
        @JsonProperty("rssi") int rssi
  ) {
    public WiFiStatus(Config config) {
      this(config.getBoolean("connected"), config.getString("ssid"), config.getString("ip"), config.getInt("rssi"));
    }
  }

  public record CloudStatus(
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("connected") boolean connected
  ) {
    public CloudStatus(Config config) {
      this(config.getBoolean("enabled"), config.getBoolean("connected"));
    }
  }

  public record MqttStatus(
        @JsonProperty("connected") boolean connected
  ) {}
  
  public record TempStatus(
        @JsonProperty("tC") double tC,
        @JsonProperty("tF") double tF,
        @JsonProperty("is_valid") boolean isValid
  ) {}
  
  public record ShellyClientContext(
        @Nullable String mac,
        @Nullable String hostname,
        @Nullable Double powerFactor
  ) implements ClientContext {}

  @Getter
  @Setter
  @AllArgsConstructor(staticName = "create")
  protected static class WebsocketContext {
    private final InetAddress remoteAddress;
    private final ActorRef<WebsocketOutput.Command> output;
    private final UniqueKillSwitch killSwitch;
    private final SourceQueueWithComplete<WebsocketProcessPendingEmGetStatusRequest> throttlingQueue;
    private Rpc.Request lastEmGetStatusRequest;
    
    public void close() {
      killSwitch.shutdown();
    }
    
    public void handleEmGetStatusRequest(@NotNull Message wsMessage, @NotNull Rpc.Request emGetStatusRequest) {
      lastEmGetStatusRequest = emGetStatusRequest;
      throttlingQueue.offer(new WebsocketProcessPendingEmGetStatusRequest(this, wsMessage));
    }
  }

  @Getter
  @Setter
  @AllArgsConstructor
  protected static class UdpClientContext {
    private final InetSocketAddress remote;
    private final UniqueKillSwitch killSwitch;
    private final SourceQueueWithComplete<UdpClientProcessPendingEmGetStatusRequest> throttlingQueue;
    private Rpc.Request lastEmGetStatusRequest;
    private Instant lastEmGetStatusRequestTime;
    
    public static UdpClientContext of(@NotNull InetSocketAddress remote,
                                      @NotNull UniqueKillSwitch killSwitch,
                                      @NotNull SourceQueueWithComplete<UdpClientProcessPendingEmGetStatusRequest> throttlingQueue) {
      return new UdpClientContext(remote, killSwitch, throttlingQueue, null, Instant.now());
    }

    public void handleEmGetStatusRequest(@NotNull Datagram datagram, @NotNull Rpc.Request emGetStatusRequest) {
      lastEmGetStatusRequest = emGetStatusRequest;
      lastEmGetStatusRequestTime = Instant.now();
        throttlingQueue.offer(new UdpClientProcessPendingEmGetStatusRequest(this, datagram));
    }
    
    public void close() {
      killSwitch.shutdown();
    }
  }
}
