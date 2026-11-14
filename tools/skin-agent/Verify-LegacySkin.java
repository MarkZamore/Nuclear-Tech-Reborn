import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import net.minecraft.launchwrapper.LaunchClassLoader;

final class LegacySkinProbe {
    public static void main(String[] args) throws Exception {
        String[] dependencies = System.getProperty("java.class.path").split(java.io.File.pathSeparator);
        URL[] libraries = new URL[dependencies.length + 1];
        libraries[0] = new java.io.File(args[0]).toURI().toURL();
        for (int index = 0; index < dependencies.length; index++) {
            libraries[index + 1] = new java.io.File(dependencies[index]).toURI().toURL();
        }
        try (LaunchClassLoader loader = new LaunchClassLoader(libraries)) {
            Class<?> auth = loader.loadClass("com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService");
            Object service = auth.getMethod("createMinecraftSessionService").invoke(
                    auth.getConstructor(Proxy.class, String.class).newInstance(Proxy.NO_PROXY, "qa"));
            Class<?> profile = loader.loadClass("com.mojang.authlib.GameProfile");
            Class<?> type = service.getClass();
            Method getTextures = type.getMethod("getTextures", profile, boolean.class);
            Method domain = type.getDeclaredMethod("isWhitelistedDomain", String.class);
            domain.setAccessible(true);

            Object own = profile.getConstructor(UUID.class, String.class)
                    .newInstance(UUID.fromString(args[1]), "QaSkin");
            Map<?, ?> textures = (Map<?, ?>) getTextures.invoke(service, own, true);
            Object skin = textures.values().stream().findFirst().orElse(null);
            if (skin == null) throw new AssertionError("The launcher skin was not returned");
            require(args[2].equals(skin.getClass().getMethod("getUrl").invoke(skin)), "Wrong launcher texture");
            require("slim".equals(skin.getClass().getMethod("getMetadata", String.class)
                    .invoke(skin, "model")), "The skin model was not preserved");
            require(Boolean.FALSE.equals(domain.invoke(null, args[2])), "Registered loopback leaked into ordinary domain check");
            require(Boolean.FALSE.equals(domain.invoke(null, args[2] + "x")), "Unregistered loopback was accepted");
            require(Boolean.FALSE.equals(domain.invoke(null, "https://example.org/texture/foo")), "Foreign domain was accepted");
            require(Boolean.TRUE.equals(domain.invoke(null, "https://textures.minecraft.net/texture/foo")), "Mojang domain changed");

            Object other = profile.getConstructor(UUID.class, String.class)
                    .newInstance(UUID.randomUUID(), "OtherPlayer");
            Object propertyMap = profile.getMethod("getProperties").invoke(other);
            Class<?> property = loader.loadClass("com.mojang.authlib.properties.Property");
            Object unsigned = property.getConstructor(String.class, String.class)
                    .newInstance("textures", Base64.getEncoder().encodeToString(
                            ("{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/native\"}}}")
                                    .getBytes(StandardCharsets.UTF_8)));
            propertyMap.getClass().getMethod("put", Object.class, Object.class).invoke(propertyMap, "textures", unsigned);
            Map<?, ?> vanillaTextures = (Map<?, ?>) getTextures.invoke(service, other, false);
            Object vanilla = vanillaTextures.values().iterator().next();
            require("https://textures.minecraft.net/texture/native".equals(vanilla.getClass().getMethod("getUrl").invoke(vanilla)),
                    "The vanilla texture path was rewritten");
            try {
                getTextures.invoke(service, other, true);
                throw new AssertionError("Signature requirement was removed for unregistered profiles");
            } catch (InvocationTargetException failure) {
                require(failure.getCause().getClass().getSimpleName().equals("InsecureTextureException"),
                        "Expected Authlib to reject unsigned unregistered textures: " + failure.getCause());
            }
            System.out.println("PASS registered skin and slim model through Authlib 1.5.25 / LaunchWrapper; original checks retained.");
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
