package lanminecraft.nuclear.skins;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Binds JourneyMap storage to the save ID returned on the current Steam connection. */
public final class JourneyMapGuestWorld {
    private static final String SAVE_ID = "lanmc_world_uuid";
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");
    private static final long CONFIG_WAIT = TimeUnit.SECONDS.toNanos(5);
    private static final AtomicReference<Connection> CURRENT = new AtomicReference<>();
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private JourneyMapGuestWorld() { }

    public static void markSteamEndpoint(InetSocketAddress endpoint) {
        if (endpoint != null && !endpoint.isUnresolved() && endpoint.getAddress().isLoopbackAddress()
                && endpoint.getPort() > 0) {
            CURRENT.set(new Connection(endpoint));
        }
    }

    public static String directoryKey(Object minecraft) {
        Connection pending = CURRENT.get();
        if (minecraft == null || pending == null) return null;
        try {
            Connection connection = findConnection(CLIENTS.get(minecraft.getClass()), minecraft);
            return connection == null ? null : connection.directoryKey();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warn(failure);
            return pendingKeyIfScoped(minecraft, pending);
        }
    }

    public static boolean awaitSave(Object journeyMapClient) {
        Connection pending = CURRENT.get();
        if (pending == null) return false;
        try {
            Access access = ACCESS.get(journeyMapClient.getClass());
            Object minecraft = access.client.minecraft.invoke(null);
            Connection connection = findConnection(access.client, minecraft);
            if (connection == null || connection.worldKey != null || connection.configReceived) return false;
            if (connection.handler() == null) return true;

            // JourneyMap normally sends this request from reset(), after it has
            // opened a mapping directory. Here it must happen before mapping starts.
            if (!connection.requested) {
                connection.requested = true;
                connection.requestStarted = System.nanoTime();
                access.resetWaypoints();
                access.requestConfig();
            }
            return System.nanoTime() - connection.requestStarted < CONFIG_WAIT;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            pending.configReceived = true;
            warn(failure);
            return false;
        }
    }

    public static void acceptConfig(Object response) {
        if (response == null || CURRENT.get() == null) return;
        try {
            Access access = ACCESS.get(response.getClass());
            Object minecraft = access.client.minecraft.invoke(null);
            Connection connection = findConnection(access.client, minecraft);
            if (connection == null) return;
            Object handler = access.networkHandler(response);
            if (handler == null || connection.handler() != handler) return;
            String worldKey = access.worldKey(response);
            access.client.schedule.invoke(minecraft, (Runnable) () -> {
                try {
                    if (findConnection(access.client, minecraft) != connection
                            || connection.handler() != handler) return;
                    if (worldKey != null && !worldKey.equals(connection.worldKey)) {
                        if (connection.worldKey != null) return;
                        Object journeyMap = access.journeyMapClient.invoke(null);
                        // Flush a fallback session under its own path before selecting the save.
                        if (Boolean.TRUE.equals(access.isMapping.invoke(journeyMap))) {
                            access.stopMapping.invoke(journeyMap);
                        }
                        connection.worldKey = worldKey;
                        access.resetWaypoints();
                    }
                    connection.configReceived = true;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    connection.configReceived = true;
                    warn(failure);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warn(failure);
        }
    }

    private static String pendingKeyIfScoped(Object minecraft, Connection connection) {
        if (CURRENT.get() != connection) return null;
        try {
            Object data = minecraft.getClass().getMethod("func_147104_D").invoke(minecraft);
            return data != null && connection.matchesServerData(data.getClass().getField("field_78845_b").get(data))
                    ? connection.pendingKey : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private static void warn(Throwable failure) {
        if (WARNED.compareAndSet(false, true)) {
            System.err.println("[Nuclear Tech Reborn] JourneyMap save identity unavailable: " + failure);
        }
    }

    private static Connection findConnection(Client client, Object minecraft) throws ReflectiveOperationException {
        Connection connection = CURRENT.get();
        if (connection == null || minecraft == null) return null;
        Object handler = client.connection.invoke(minecraft);
        if (handler != null && connection.handler() == handler) return connection;
        if (connection.wasBound()) {
            CURRENT.compareAndSet(connection, null);
            return null;
        }
        Object serverData = client.serverData.invoke(minecraft);
        if (serverData == null || !connection.matchesServerData(client.serverAddress.get(serverData))) {
            return null;
        }
        if (handler == null) return connection;
        Object manager = client.networkManager.invoke(handler);
        if (!connection.endpoint.equals(client.remoteAddress.invoke(manager))) return null;
        return connection.bind(handler) ? connection : null;
    }

    private static IllegalStateException unavailable(ReflectiveOperationException failure) {
        return new IllegalStateException("Pinned JourneyMap client API is unavailable", failure);
    }

    private static final ClassValue<Client> CLIENTS = new ClassValue<Client>() {
        @Override protected Client computeValue(Class<?> minecraft) {
            try {
                return new Client(minecraft);
            } catch (ReflectiveOperationException failure) {
                throw unavailable(failure);
            }
        }
    };

    private static final ClassValue<Access> ACCESS = new ClassValue<Access>() {
        @Override protected Access computeValue(Class<?> context) {
            try {
                return new Access(context.getClassLoader());
            } catch (ReflectiveOperationException failure) {
                throw unavailable(failure);
            }
        }
    };

    private static final class Connection {
        final InetSocketAddress endpoint;
        final String serverAddress;
        final String pendingKey = "pending-" + UUID.randomUUID();
        private volatile WeakReference<Object> connection;
        volatile String worldKey;
        volatile boolean configReceived;
        boolean requested;
        long requestStarted;

        Connection(InetSocketAddress endpoint) {
            this.endpoint = endpoint;
            this.serverAddress = endpoint.getAddress().getHostAddress() + ":" + endpoint.getPort();
        }

        boolean matchesServerData(Object address) {
            return serverAddress.equals(address);
        }

        boolean wasBound() {
            return connection != null;
        }

        Object handler() {
            WeakReference<Object> reference = connection;
            return reference == null ? null : reference.get();
        }

        synchronized boolean bind(Object handler) {
            if (connection == null) connection = new WeakReference<>(handler);
            return CURRENT.get() == this && handler() == handler;
        }

        String directoryKey() {
            String key = worldKey;
            return key == null ? pendingKey : key;
        }
    }

    private static final class Client {
        final Method minecraft, serverData, connection, networkManager, remoteAddress, schedule;
        final Field serverAddress;

        Client(Class<?> type) throws ReflectiveOperationException {
            minecraft = type.getMethod("func_71410_x");
            serverData = type.getMethod("func_147104_D");
            serverAddress = serverData.getReturnType().getField("field_78845_b");
            connection = type.getMethod("func_147114_u");
            networkManager = connection.getReturnType().getMethod("func_147298_b");
            remoteAddress = networkManager.getReturnType().getMethod("func_74430_c");
            schedule = type.getMethod("func_152344_a", Runnable.class);
        }
    }

    private static final class Access {
        final Client client;
        final Constructor<?> request;
        final Method send, responseContext, responseJson, jsonGet, isPrimitive, jsonString;
        final Method journeyMapClient, isMapping, stopMapping, resetWaypoints;
        final Field netHandler, waypointStore;

        Access(ClassLoader loader) throws ReflectiveOperationException {
            client = CLIENTS.get(Class.forName("net.minecraft.client.Minecraft", false, loader));
            Class<?> requestType = Class.forName("journeymap.common.network.GetClientConfig", false, loader);
            request = requestType.getConstructor();
            send = requestType.getMethod("send");
            Class<?> response = Class.forName("journeymap.common.network.impl.Response", false, loader);
            responseContext = response.getMethod("getContext");
            netHandler = responseContext.getReturnType().getField("netHandler");
            responseJson = response.getMethod("getAsJson");
            Class<?> gson = responseJson.getReturnType();
            jsonGet = gson.getMethod("get", String.class);
            isPrimitive = jsonGet.getReturnType().getMethod("isJsonPrimitive");
            jsonString = jsonGet.getReturnType().getMethod("getAsString");
            Class<?> mod = Class.forName("journeymap.common.Journeymap", false, loader);
            journeyMapClient = mod.getMethod("getClient");
            isMapping = journeyMapClient.getReturnType().getMethod("isMapping");
            stopMapping = journeyMapClient.getReturnType().getMethod("stopMapping");
            Class<?> waypoints = Class.forName("journeymap.client.waypoint.WaypointStore", false, loader);
            waypointStore = waypoints.getField("INSTANCE");
            resetWaypoints = waypoints.getMethod("reset");
        }

        void requestConfig() throws ReflectiveOperationException {
            send.invoke(request.newInstance());
        }

        void resetWaypoints() throws ReflectiveOperationException {
            resetWaypoints.invoke(waypointStore.get(null));
        }

        Object networkHandler(Object response) throws ReflectiveOperationException {
            Object context = responseContext.invoke(response);
            return context == null ? null : netHandler.get(context);
        }

        String worldKey(Object response) throws ReflectiveOperationException {
            Object json = responseJson.invoke(response);
            if (json == null) return null;
            Object settings = jsonGet.invoke(json, "settings");
            if (settings == null || !responseJson.getReturnType().isInstance(settings)) return null;
            Object value = jsonGet.invoke(settings, SAVE_ID);
            if (value == null || !Boolean.TRUE.equals(isPrimitive.invoke(value))) return null;
            String id = (String) jsonString.invoke(value);
            return id != null && UUID_TEXT.matcher(id).matches() ? "world-" + UUID.fromString(id) : null;
        }
    }
}
