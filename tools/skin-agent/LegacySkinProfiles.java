package lanminecraft.nuclear.skins;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Collection;
import java.util.UUID;

/** Limits Authlib's unsigned texture route to a profile replaced by the launcher. */
public final class LegacySkinProfiles {
    private static final ThreadLocal<String> APPLIED_URL = new ThreadLocal<String>();

    private LegacySkinProfiles() { }

    public static boolean applyRegisteredSkin(Object profile) {
        APPLIED_URL.remove();
        if (profile == null || System.getProperty("minecraft.portable.skin.registry") == null) return false;
        try {
            UUID id = (UUID) profile.getClass().getMethod("getId").invoke(profile);
            if (id == null) return false;
            UUID skinId = id;
            String url = PortableSkinProfiles.registeredUrl(skinId);
            if (url == null) {
                skinId = tunnelSkinId(id);
                if (skinId == null) return false;
                url = PortableSkinProfiles.registeredUrl(skinId);
            }
            if (!validUrl(skinId, url)) return false;
            Object properties = profile.getClass().getMethod("getProperties").invoke(profile);
            Method propertiesNamed = properties.getClass().getMethod("get", Object.class);
            propertiesNamed.setAccessible(true);
            Collection<?> before = (Collection<?>) propertiesNamed.invoke(properties, "textures");
            Object prior = before.size() == 1 ? before.iterator().next() : null;
            PortableSkinProfiles.apply(profile, skinId);
            Collection<?> after = (Collection<?>) propertiesNamed.invoke(properties, "textures");
            if (after.size() != 1 || !url.equals(PortableSkinProfiles.registeredUrl(skinId))) return false;
            Object replacement = after.iterator().next();
            if (replacement == prior || !projectedTextureContains(replacement, url)) return false;
            APPLIED_URL.set(url);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            System.err.println("[Nuclear Tech Reborn] Could not attach the launcher skin to this profile.");
            return false;
        }
    }

    private static UUID tunnelSkinId(UUID tunnelId) {
        if (tunnelId.version() != 3 || tunnelId.variant() != 2) return null;
        String configuredPath = System.getProperty("minecraft.portable.identity.registry", "").trim();
        if (configuredPath.isEmpty()) return null;
        Path path = Paths.get(configuredPath);
        String tunnelKey = tunnelId.toString();
        UUID skinId = null;
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String[] fields = line.split("\\|", -1);
                if (fields.length != 3 || fields[0].isEmpty() || !tunnelKey.equalsIgnoreCase(fields[2])) continue;
                UUID listed = UUID.fromString(fields[1]);
                if (!listed.toString().equalsIgnoreCase(fields[1]) || (skinId != null && !skinId.equals(listed))) {
                    return null;
                }
                skinId = listed;
            }
        } catch (IOException | IllegalArgumentException failure) {
            return null;
        }
        return skinId;
    }

    private static boolean projectedTextureContains(Object property, String url) throws ReflectiveOperationException {
        String value = (String) property.getClass().getMethod("getValue").invoke(property);
        String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        return decoded.contains("\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"");
    }

    public static boolean isRegisteredUrl(String url) {
        String applied = APPLIED_URL.get();
        APPLIED_URL.remove();
        return applied != null && applied.equals(url) && PortableSkinProfiles.isRegisteredUrl(url);
    }

    private static boolean validUrl(UUID player, String value) {
        if (value == null) return false;
        try {
            URI url = new URI(value);
            String path = url.getRawPath();
            return "http".equals(url.getScheme()) && "127.0.0.1".equals(url.getHost())
                    && url.getPort() > 0 && url.getPort() <= 65535 && url.getRawUserInfo() == null
                    && url.getRawQuery() == null && url.getRawFragment() == null && path != null
                    && path.matches("/skin/" + player + "/[0-9A-Fa-f]{64}");
        } catch (URISyntaxException failure) {
            return false;
        }
    }
}
