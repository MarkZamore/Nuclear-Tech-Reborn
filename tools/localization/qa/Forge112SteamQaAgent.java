package lanminecraft.qa;

import java.awt.image.BufferedImage;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Runs only in the isolated Forge localization fixture. */
public final class Forge112SteamQaAgent {
    private static Instrumentation instrumentation;
    private static Path evidence;
    private static Object client;
    private static List<String[]> dictionary;
    private static int step, waiting;
    private static boolean stopped;
    private static final String[] LANGUAGES = {"en_us", "ru_ru", "ru_ru", "ru_ru", "ru_ru", "en_us"};
    private static final int[] MODES = {0, 0, 1, 2, 3, 0};

    public static void premain(String ignored, Instrumentation value) throws Exception {
        evidence = Paths.get(System.getProperty("lanminecraft.steamqa.evidence")).toRealPath();
        Path run = evidence.getParent().toRealPath();
        if (!run.getParent().getFileName().toString().equals("agent-runs")
                || !run.resolve("game").equals(Paths.get("").toRealPath())) {
            throw new IllegalStateException("Expected an isolated localization fixture");
        }
        instrumentation = value;
        dictionary = new ArrayList<>();
        List<String> lines = Files.readAllLines(run.resolve("game/config/lanminecraft/nuclear-book-ru.tsv"),
                StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            dictionary.add(line.split("\t", -1));
        }
    }

    public static void runClientTickTasks() {
        if (stopped) return;
        try {
            if (client == null) {
                for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
                    if (candidate.getName().equals("net.minecraft.client.Minecraft")) {
                        client = candidate.getMethod("func_71410_x").invoke(null);
                        break;
                    }
                }
                if (client == null) return;
            }
            Class<?> minecraft = client.getClass();
            ClassLoader loader = minecraft.getClassLoader();
            if (step == 0 && waiting == 0) {
                Object screen = minecraft.getField("field_71462_r").get(client);
                if (screen == null || !(screen.getClass().getName().contains("GuiCustom")
                        || screen.getClass().getName().endsWith("GuiMainMenu"))) return;
            }
            if (waiting == 0) {
                selectLanguageAndOpenNote(minecraft, loader, LANGUAGES[step], MODES[step]);
                waiting = 20;
                return;
            }
            if (--waiting != 0) return;
            Object framebuffer = minecraft.getMethod("func_147110_a").invoke(client);
            Class<?> helpers = loader.loadClass("net.minecraft.util.ScreenShotHelper");
            BufferedImage pixels = (BufferedImage) helpers.getMethod("func_186719_a", int.class, int.class, framebuffer.getClass())
                    .invoke(null, minecraft.getField("field_71443_c").getInt(client), minecraft.getField("field_71440_d").getInt(client), framebuffer);
            Path screenshot = evidence.resolve("note-" + step + "-" + LANGUAGES[step] + "-" + MODES[step] + ".png");
            if (!ImageIO.write(pixels, "png", screenshot.toFile())) throw new IllegalStateException("PNG writer unavailable");
            if (++step == LANGUAGES.length) {
                Files.write(evidence.resolve("native-books-pass.txt"),
                        Collections.singletonList("All dictionary quest texts and item name matched in EN/RU/EN. Rendered The One Probe Read Me in four Russian modes."),
                        StandardCharsets.UTF_8);
                stopped = true;
                minecraft.getMethod("func_71400_g").invoke(client);
            }
        } catch (Throwable failure) {
            stopped = true;
            failure.printStackTrace();
            try {
                Files.write(evidence.resolve("native-books-failure.txt"), Collections.singletonList(failure.toString()), StandardCharsets.UTF_8);
                if (client != null) client.getClass().getMethod("func_71400_g").invoke(client);
            } catch (Exception ignored) {}
        }
    }

    private static void selectLanguageAndOpenNote(Class<?> minecraft, ClassLoader loader, String locale, int mode)
            throws Exception {
        Object manager = minecraft.getMethod("func_135016_M").invoke(client);
        Method current = manager.getClass().getMethod("func_135041_c");
        Method choose = manager.getClass().getMethod("func_135045_a", current.getReturnType());
        Object selected = manager.getClass().getMethod("func_191960_a", String.class).invoke(manager, locale);
        if (selected == null) throw new IllegalStateException("Language not found: " + locale);
        choose.invoke(manager, selected);
        verifyStrings(loader, "ru_ru".equals(locale), locale);
        Class<?> note = loader.loadClass("mcjty.theoneprobe.gui.GuiNote");
        Class<?> config = loader.loadClass("mcjty.theoneprobe.config.Config");
        config.getField("needsProbe").setInt(null, mode);
        Class<?> guiType = loader.loadClass("net.minecraft.client.gui.GuiScreen");
        minecraft.getMethod("func_147108_a", guiType).invoke(client, note.newInstance());
    }

    private static void verifyStrings(ClassLoader loader, boolean russian, String locale) throws Exception {
        Class<?> quest = loader.loadClass("betterquesting.api2.utils.QuestTranslation");
        Method translate = quest.getMethod("translate", String.class, Object[].class);
        String expectedTitle = "The One Probe Read Me";
        for (String[] row : dictionary) {
            String original = decode(row[1]);
            if ("q".equals(row[0])) {
                Object translated = translate.invoke(null, original, new Object[0]);
                if (!(russian ? decode(row[2]) : original).equals(translated)) {
                    throw new IllegalStateException("Wrong quest translation for " + locale + ": " + original);
                }
            } else if (russian && original.equals(expectedTitle)) {
                expectedTitle = decode(row[2]);
            }
        }
        String custom = "User-defined quest without matching localization";
        if (!custom.equals(translate.invoke(null, custom, new Object[0]))) {
            throw new IllegalStateException("User text was changed");
        }
        Class<?> item = loader.loadClass("net.minecraft.item.Item");
        Class<?> stackClass = loader.loadClass("net.minecraft.item.ItemStack");
        Object probeItem = loader.loadClass("mcjty.theoneprobe.items.ModItems").getField("probeNote").get(null);
        Object stack = stackClass.getConstructor(item).newInstance(probeItem);
        String title = (String) stackClass.getMethod("func_82833_r").invoke(stack);
        if (!expectedTitle.equals(title)) {
            throw new IllegalStateException("Wrong item name for " + locale + ": " + title);
        }
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
