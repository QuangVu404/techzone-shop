package vn.techzone;

import vn.techzone.application.TechZoneStore;
import vn.techzone.backend.CatalogBackend;
import vn.techzone.backend.CatalogClient;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

public final class CatalogBackendRegression {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void image(Path path, Color background, Color shape, int size) throws Exception {
        BufferedImage b=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB); Graphics2D g=b.createGraphics();
        try { g.setColor(background); g.fillRect(0,0,size,size); g.setColor(shape); g.fillOval(size/4,size/5,size/2,size*3/5); } finally { g.dispose(); }
        ImageIO.write(b,"png",path.toFile());
    }
    private static void imageFormats(TechZoneStore store, Path root) throws Exception {
        Path inputs = Files.createDirectory(root.resolve("format-inputs"));
        Path photos = Files.createDirectory(root.resolve("format-photos"));
        try {
            Process generator = new ProcessBuilder(Path.of(".venv-ai", "Scripts", "python.exe").toAbsolutePath().toString(),
                    Path.of("src", "test", "resources", "generate_image_formats.py").toAbsolutePath().toString(), inputs.toAbsolutePath().toString())
                    .inheritIO().start();
            check(generator.waitFor() == 0, "unable to generate image format fixtures");
            try (CatalogBackend backend = new CatalogBackend(store, photos, false)) {
                CatalogClient client = new CatalogClient(backend.baseUri(), backend.token());
                for (String name : List.of("sample.png", "sample.jpg", "sample.bmp", "sample.webp", "sample.tiff", "sample.avif", "sample.heic", "sample.ppm", "sample.gif", "no-extension")) {
                    client.setImage("CPU-14600K", inputs.resolve(name));
                    BufferedImage stored = client.image("CPU-14600K");
                    check(stored != null && stored.getWidth() == 96 && stored.getHeight() == 64, "failed to decode " + name);
                }
                client.setImage("CPU-14600K", inputs.resolve("sample.gif"));
                int gifCenter = client.image("CPU-14600K").getRGB(48, 32);
                check(((gifCenter >> 16) & 255) > 180 && (gifCenter & 255) < 80, "GIF must use its first frame");
                client.setImage("CPU-14600K", inputs.resolve("transparent.png"));
                check((client.image("CPU-14600K").getRGB(0, 0) & 0xffffff) == 0xffffff, "transparent image must use white background");
                client.setImage("CPU-14600K", inputs.resolve("oriented.jpg"));
                BufferedImage oriented = client.image("CPU-14600K");
                check(oriented.getWidth() == 64 && oriented.getHeight() == 96, "EXIF orientation must be applied");
                client.setImage("CPU-14600K", inputs.resolve("sample.heic"));
                check(client.searchImage(inputs.resolve("sample.webp")).contains("CPU-14600K"), "search should decode WEBP");
                check(client.searchImage(inputs.resolve("no-extension")).contains("CPU-14600K"), "search should detect HEIC by content");
                boolean tooManyPixels = false;
                try { client.setImage("CPU-14600K", inputs.resolve("too-many-pixels.png")); }
                catch (java.io.IOException ex) { tooManyPixels = ex.getMessage().contains("64 triệu"); }
                check(tooManyPixels && client.image("CPU-14600K") != null, "oversized pixel dimensions must be rejected without replacing the photo");
                Path large = inputs.resolve("too-large.bin");
                try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(large.toFile(), "rw")) { f.setLength(30L * 1024 * 1024 + 1); }
                boolean tooManyBytes = false;
                try { client.setImage("CPU-14600K", large); } catch (java.io.IOException ex) { tooManyBytes = ex.getMessage().contains("30 MB"); }
                check(tooManyBytes, "oversized file must be rejected");
            }
        } finally {
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(photos)) { for (Path p : paths) Files.delete(p); }
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(inputs)) { for (Path p : paths) Files.delete(p); }
            Files.deleteIfExists(photos); Files.deleteIfExists(inputs);
        }
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory(Path.of("target"),"catalog-test-");
        Path images=Files.createDirectory(root.resolve("images")); Path red=root.resolve("red.png"), blue=root.resolve("blue.png"), query=root.resolve("query.png"), invalid=root.resolve("invalid.png");
        image(red,Color.WHITE,Color.RED,128); image(blue,Color.WHITE,Color.BLUE,128); image(query,Color.WHITE,Color.RED,256); Files.writeString(invalid,"not an image");
        TechZoneStore store=new TechZoneStore();
        try {
            try(CatalogBackend backend=new CatalogBackend(store,images,false)) {
                CatalogClient client=new CatalogClient(backend.baseUri(),backend.token());
                check(client.productsJson().contains("\"specs\""),"catalogue API has product specs");
                String product=client.productJson("CPU-14600K");
                check(product.contains("Intel Core i5") && product.contains("\"available\"") && product.contains("\"warrantyMonths\""),"product API has details");
                check(client.productDetails("CPU-14600K").contains("socket"),"product details are served by backend");
                String rec = client.productRecommendationsJson("CPU-14600K");
                check(rec.contains("\"strategy\"") && rec.contains("\"inferenceMs\"") && rec.contains("\"compatibility\""), "recommendation API provides rank, reason and timings");
                store.cart().clear(); store.addToCart("CPU-7800X3D", 1);
                String constrained = client.productRecommendationsJson("CPU-14600K");
                check(!constrained.contains("MB-B760-D5") && !constrained.contains("MB-B760-D4"), "recommendation API filters against the whole cart");
                store.cart().clear();
                HttpResponse<String> denied=HttpClient.newHttpClient().send(HttpRequest.newBuilder(backend.baseUri().resolve("/v1/products")).GET().build(),HttpResponse.BodyHandlers.ofString());
                check(denied.statusCode()==401,"backend rejects missing token");
                check(client.searchImage(query).isEmpty(),"empty image catalogue");
                client.setImage("CPU-14600K",red); client.setImage("GPU-4060",blue);
                check(client.image("CPU-14600K")!=null,"stored photo is retrievable");
                check(client.productJson("CPU-14600K").contains("\"imageAvailable\":true"),"product API reports image availability");
                List<String> matches=client.searchImage(query);
                check(!matches.isEmpty() && matches.get(0).equals("CPU-14600K"),"visual ranking uses image rather than text");
                check(Files.isRegularFile(images.resolve(".techzone-image-embeddings.npz")),"visual embeddings are cached");
                client.setImage("GPU-4060", red);
                check(client.searchImage(query).contains("GPU-4060"),"updated product photo invalidates its cached embedding");
                client.setImage("GPU-4060", blue);
                boolean rejected=false; try { client.setImage("CPU-14600K",invalid); } catch(java.io.IOException ex) { rejected=true; }
                check(rejected && client.image("CPU-14600K")!=null,"invalid replacement does not overwrite saved photo");
                store.balance("CPU-14600K").available=0;
                check(!client.searchImage(query).contains("CPU-14600K"),"out of stock excluded");
            }
            store.balance("CPU-14600K").available=1;
            try(CatalogBackend reopened=new CatalogBackend(store,images,false)) {
                CatalogClient client=new CatalogClient(reopened.baseUri(),reopened.token());
                List<String> ids=client.searchImage(query);
                check(ids.get(0).equals("CPU-14600K"),"photo index rebuilt after restart");

            }
            Path seeded = Files.createDirectory(root.resolve("seeded"));
            try {
                byte[] userPhoto = Files.readAllBytes(red);
                Files.write(seeded.resolve("CPU-14600K.png"), userPhoto);
                try (CatalogBackend withDefaults = new CatalogBackend(store, seeded)) {
                    check(java.util.Arrays.equals(userPhoto, Files.readAllBytes(seeded.resolve("CPU-14600K.png"))), "default images do not overwrite a user photo");
                    for (var p : store.products()) {
                        Path photo = seeded.resolve(p.id + ".png");
                        check(Files.isRegularFile(photo) && ImageIO.read(photo.toFile()) != null, "missing or corrupt default image: " + p.id);
                    }
                    check(new CatalogClient(withDefaults.baseUri(), withDefaults.token()).productJson("GPU-4060").contains("\"imageAvailable\":true"), "default photo visible in catalogue API");
                }
            } finally {
                try (DirectoryStream<Path> paths = Files.newDirectoryStream(seeded, "*.png")) {
                    for (Path photo : paths) Files.delete(photo);
                }
                Files.deleteIfExists(seeded);
            }
            imageFormats(store, root);
            System.out.println("Catalogue backend: product API, access token, visual search, image persistence and stock filtering passed");
        } finally {
            Files.deleteIfExists(images.resolve("CPU-14600K.png")); Files.deleteIfExists(images.resolve("GPU-4060.png"));
            Files.deleteIfExists(images.resolve(".techzone-image-embeddings.npz"));
            Files.deleteIfExists(images); Files.deleteIfExists(red); Files.deleteIfExists(blue); Files.deleteIfExists(query); Files.deleteIfExists(invalid); Files.deleteIfExists(root);
        }
    }
}
