package vn.techzone.backend;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import vn.techzone.application.TechZoneStore;
import vn.techzone.application.SearchUseCase;
import vn.techzone.application.RecommendUseCase;
import vn.techzone.application.CheckoutUseCase;
import vn.techzone.application.AllocateSerialUseCase;
import vn.techzone.application.WarrantyUseCase;
import vn.techzone.application.ProvisionalCompatibilityService;
import vn.techzone.domain.TechZoneModels.ProductVariant;
import vn.techzone.domain.TechZoneModels.InventoryBalance;
import vn.techzone.domain.TechZoneModels.SalesOrder;
import vn.techzone.domain.TechZoneModels.PaymentVerdict;
import vn.techzone.domain.TechZoneModels.Role;
import vn.techzone.infrastructure.PythonModels;
import vn.techzone.infrastructure.TechZoneCatalogAdapter;
import vn.techzone.infrastructure.TechZoneRankingAdapter;
import vn.techzone.infrastructure.TechZoneOrderAdapter;
import vn.techzone.infrastructure.TechZoneInventoryAdapter;
import vn.techzone.infrastructure.TechZoneWarrantyAdapter;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Local catalogue and web backend backed by the persisted demo store. */
public final class CatalogBackend implements AutoCloseable {
    private static final int MAX_IMAGE = 30 * 1024 * 1024;
    private static final int MAX_SOURCE_IMAGE = 8 * 1024 * 1024;
    private static final HttpClient SOURCE_IMAGE_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile TechZoneStore store;
    private volatile SqliteStore database;
    private volatile SemanticSearch semanticSearch;
    private final AdvisorChat advisorChat = new AdvisorChat();
    private final Path imageDirectory;
    private final HttpServer server;
    private final ExecutorService executor;
    private final ExecutorService imagePrefetch = Executors.newSingleThreadExecutor(r -> {
        Thread t=new Thread(r,"techzone-image-prefetch"); t.setDaemon(true); return t;
    });
    private final java.util.concurrent.atomic.AtomicBoolean imagePrefetchRunning = new java.util.concurrent.atomic.AtomicBoolean();
    private final Object storeLock = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean marketRefresh = new java.util.concurrent.atomic.AtomicBoolean();
    private final String token = UUID.randomUUID().toString() + UUID.randomUUID();
    private final Set<String> index = ConcurrentHashMap.newKeySet();
    private record ProductData(String id,String productId,String productName,String sku,String name,String category,String brand,String description,String color,long price,long salePrice,long effectivePrice,int warrantyMonths,boolean active,int available,boolean imageAvailable,Map<String,String> specs) { }
    private record RecommendationData(TechZoneStore.RecommendationResult result, List<ProductData> products, List<String> reasons) { }
    private record CheckoutData(long revision, byte[] state, String message, String orderId, boolean confirmed) { }
    private record OperationData(long revision,byte[] state,String message) { }
    private record SemanticSnapshot(int revision,boolean available,List<SemanticSearch.Document> documents) { }
    private record AdvisorData(AdvisorService.Answer answer,List<ProductData> products,boolean semanticUsed) { }
    private record CandidateSnapshot(int revision,boolean available,String query,List<SemanticSearch.Document> documents) { }

    public CatalogBackend(TechZoneStore store, Path imageDirectory) throws IOException {
        this(store, imageDirectory, true);
    }

    public CatalogBackend(TechZoneStore store, Path imageDirectory, boolean seedDemoImages) throws IOException {
        this.store = store;
        this.imageDirectory = imageDirectory.toAbsolutePath().normalize();
        Files.createDirectories(this.imageDirectory);
        if (seedDemoImages) {
            for (ProductVariant product : store.products()) {
                if (!product.active) continue;
                if (!product.id.matches("[A-Za-z0-9_-]+")) continue;
                Path destination = this.imageDirectory.resolve(product.id + ".png");
                if (Files.exists(destination)) continue;
                try (InputStream source = CatalogBackend.class.getResourceAsStream("/vn/techzone/catalog/" + product.id + ".png")) {
                    if (source != null) {
                        Path temp = Files.createTempFile(this.imageDirectory, ".default-", ".png");
                        try {
                            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
                            try { Files.move(temp, destination); }
                            catch (FileAlreadyExistsException ignored) { /* Preserve a user photo. */ }
                        } finally { Files.deleteIfExists(temp); }
                    }
                }
            }
        }
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(this.imageDirectory, "*.png")) {
            for (Path path : paths) {
                String name=path.getFileName().toString(); String id=name.substring(0,name.length()-4);
                if (!id.matches("[A-Za-z0-9_-]+")) continue;
                try { if (Files.size(path) <= MAX_IMAGE) { VisualDescriptor.decode(Files.readAllBytes(path)); index.add(id); } }
                catch (IOException ignored) { /* A broken photo is ignored; other products stay searchable. */ }
            }
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newFixedThreadPool(2, r -> { Thread t=new Thread(r,"techzone-catalog-api"); t.setDaemon(true); return t; });
        server.setExecutor(executor); server.createContext("/v1/", this::handle); server.createContext("/", this::web); server.start();
        if(seedDemoImages) prefetchSourceImages(store.products().stream().map(this::copy).toList());
    }
    public java.net.URI baseUri() { return java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    public String token() { return token; }
    public void attachDatabase(SqliteStore database) { this.database = Objects.requireNonNull(database); }
    public void attachSemanticSearch(SemanticSearch search) { this.semanticSearch=Objects.requireNonNull(search); }
    private <T> T withStore(Callable<T> action) throws Exception {
        synchronized (storeLock) { return action.call(); }
    }
    private ProductData copy(ProductVariant p) {
        boolean remoteImage=p.spec("sourceProvider").equals("GEARVN")
                && p.spec("sourceImageUrl").startsWith("https://cdn.hstatic.net/");
        return new ProductData(p.id,p.productId(),p.productName(),p.sku,p.name,p.category,p.brand,p.description,p.color,p.price,p.salePrice,p.effectivePrice(),p.warrantyMonths,p.active,store.available(p.id),index.contains(p.id)||remoteImage,new LinkedHashMap<>(p.specs));
    }
    private boolean cacheSourceImage(ProductData product,Path destination) throws IOException {
        String url=product.specs().getOrDefault("sourceImageUrl","");
        if(!"GEARVN".equals(product.specs().get("sourceProvider"))
                || !url.matches("https://cdn\\.hstatic\\.net/[^\\s]{1,1000}")) return false;
        Path temporary=Files.createTempFile(imageDirectory,".gearvn-image-",".tmp");
        try {
            HttpRequest request=HttpRequest.newBuilder(java.net.URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("User-Agent","TechZone-Demo/1.0").header("Accept","image/jpeg,image/png,image/webp,image/avif").GET().build();
            HttpResponse<InputStream> response;
            try { response=SOURCE_IMAGE_CLIENT.send(request,HttpResponse.BodyHandlers.ofInputStream()); }
            catch(InterruptedException ex) { Thread.currentThread().interrupt(); return false; }
            try(InputStream body=response.body()) {
                String type=response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                if(response.statusCode()!=200 || !(type.startsWith("image/jpeg") || type.startsWith("image/png")
                        || type.startsWith("image/webp") || type.startsWith("image/avif"))) return false;
                byte[] bytes=body.readNBytes(MAX_SOURCE_IMAGE+1);
                if(bytes.length>MAX_SOURCE_IMAGE) return false;
                BufferedImage decoded;
                try { decoded=type.startsWith("image/jpeg") || type.startsWith("image/png")
                        ? VisualDescriptor.decode(bytes) : ImageNormalizer.normalize(bytes); }
                catch(InterruptedException ex) { Thread.currentThread().interrupt(); return false; }
                if(!ImageIO.write(decoded,"png",temporary.toFile())) return false;
            }
            try { Files.move(temporary,destination); }
            catch(FileAlreadyExistsException ignored) { /* Keep a photo uploaded while this download was in flight. */ }
            index.add(product.id());
            return true;
        } finally { Files.deleteIfExists(temporary); }
    }
    private void prefetchSourceImages(List<ProductData> products) {
        if(!imagePrefetchRunning.compareAndSet(false,true)) return;
        imagePrefetch.execute(() -> {
            try {
                for(ProductData p:products) {
                    if(Thread.currentThread().isInterrupted()) break;
                    if(!p.active() || !"GEARVN".equals(p.specs().get("sourceProvider")) || index.contains(p.id())) continue;
                    if(!p.id().matches("[A-Za-z0-9_-]+")) continue;
                    try { cacheSourceImage(p,imageDirectory.resolve(p.id()+".png")); }
                    catch(IOException | RuntimeException ignored) { /* A later request can retry an unavailable image. */ }
                }
            } finally { imagePrefetchRunning.set(false); }
        });
    }
    private void clearGearVNImageCache() throws IOException {
        try(DirectoryStream<Path> paths=Files.newDirectoryStream(imageDirectory,"GVN-*.png")) {
            for(Path path:paths) {
                String name=path.getFileName().toString();
                index.remove(name.substring(0,name.length()-4));
                Files.deleteIfExists(path);
            }
        }
    }
    private static SemanticSearch.Document semanticDocument(ProductVariant product) {
        StringBuilder text = new StringBuilder(product.name).append(' ').append(product.sku).append(' ')
                .append(product.category).append(' ').append(product.brand);
        if (!product.id.startsWith("PCP-")) text.append(' ').append(product.description);
        if(!product.spec("sourceDescription").isBlank() && !product.spec("sourceDescription").equals(product.description))
            text.append(' ').append(product.spec("sourceDescription"));
        product.specs.forEach((key, value) -> {
            if (!key.startsWith("source") && !key.equals("listingSummary") && !key.startsWith("Nguồn") && !key.startsWith("Ảnh") && !key.startsWith("Giá")
                    && value != null && !value.isBlank()) text.append(' ').append(key).append(' ').append(value);
        });
        return new SemanticSearch.Document(product.id, text.toString());
    }
    private static int sameCategoryScore(ProductVariant primary,ProductVariant candidate) {
        Set<String> words=new HashSet<>();
        for(String token:TechZoneStore.normalize(primary.name).split("\\s+")) if(token.length()>=3) words.add(token);
        int score=primary.brand.equalsIgnoreCase(candidate.brand)?3:0;
        for(String token:TechZoneStore.normalize(candidate.name).split("\\s+")) if(words.contains(token)) score++;
        return score;
    }
    private static String safeShoppingIntro(String generated,boolean hasProducts) {
        if(generated==null) return "";
        List<String> safe=new ArrayList<>();
        for(String sentence:generated.split("(?<=[.!?])\\s+")) {
            String lower=sentence.toLowerCase(Locale.ROOT);
            if(sentence.contains("?") || sentence.matches("(?s).*[0-9].*")
                    || lower.matches("(?s).*(đã thêm|đã mua|đã thanh toán|tương thích|giá bán|đảm bảo|chắc chắn|đáp ứng|nvidia|intel|amd|apple|samsung).*" )
                    || (!hasProducts && lower.matches("(?s).*(đã tìm|đã chọn|có sẵn|tìm được).*"))) continue;
            if(!sentence.isBlank()) safe.add(sentence.trim());
            if(safe.size()==2) break;
        }
        return String.join(" ",safe);
    }
    private AdvisorData advisorData(String message,String context,String combined,SemanticSnapshot snapshot,Map<String,Double> scores) {
        boolean semanticUsed=scores!=null && snapshot.revision()==store.catalogRevision();
        AdvisorService.Answer answer=AdvisorService.answer(store,message,context,semanticUsed?scores:null);
        if(semanticUsed && answer.reply().startsWith("Bạn có thể hỏi")
                && scores.getOrDefault("__intent_build__",0.0)>=0.50
                && scores.getOrDefault("__intent_build__",0.0)>=scores.getOrDefault("__intent_question__",0.0)+0.05
                && scores.getOrDefault("__intent_build__",0.0)>=scores.getOrDefault("__intent_product__",0.0)-0.03
                && AdvisorService.budget(combined)>0)
            answer=AdvisorService.answer(store,message,context+" cấu hình PC ",scores);
        if(semanticUsed && answer.configuration() && answer.products().isEmpty()
                && answer.reply().startsWith("Chưa tìm được bộ linh kiện")) {
            Set<String> categories=new HashSet<>();
            long budget=answer.budget();
            List<ProductVariant> references=store.products().stream()
                    .filter(p -> p.active && "GEARVN".equals(p.spec("sourceProvider")))
                    .filter(p -> Set.of("CPU","Mainboard","RAM","GPU","SSD","PSU","Tản nhiệt","Case").contains(p.category))
                    .filter(p -> p.effectivePrice()>0 && p.effectivePrice()<=budget)
                    .filter(p -> scores.getOrDefault(p.id,-1.0)>=0.25)
                    .sorted(Comparator.comparingDouble((ProductVariant p)->scores.getOrDefault(p.id,-1.0)).reversed())
                    .filter(p -> categories.add(p.category)).limit(5).toList();
            if(!references.isEmpty()) answer=new AdvisorService.Answer(
                    "Chưa đủ thông số và dữ liệu tương thích để lập cấu hình đã kiểm chứng. "
                    +"Các sản phẩm dưới đây chỉ là kết quả tham khảo theo nhu cầu, không phải một bộ máy hoặc cam kết phù hợp ngân sách; "
                    +"cần kiểm tra thông số, tương thích, giá và tình trạng hàng trước khi mua.",references,0,budget,false);
        }
        return new AdvisorData(answer,answer.products().stream().map(this::copy).toList(),semanticUsed);
    }
    private void handle(HttpExchange e) throws IOException {
        try {
            if (!token.equals(e.getRequestHeaders().getFirst("X-TechZone-Token")) && !(e.getRequestMethod().equals("GET") && e.getRequestURI().getPath().matches("/v1/products/[A-Za-z0-9_-]+/image") && ("token="+token).equals(e.getRequestURI().getRawQuery()))) { respond(e,401,"text/plain; charset=utf-8","Không được phép".getBytes(StandardCharsets.UTF_8)); return; }
            String path=e.getRequestURI().getPath(), method=e.getRequestMethod();
            if(path.equals("/v1/product-families") && method.equals("GET")) {
                String body=withStore(() -> {
                    StringJoiner families=new StringJoiner(",","[","]");
                    for(var product:store.productFamilies()) {
                        StringJoiner variants=new StringJoiner(",","[","]");
                        for(var variant:product.variants())variants.add(json(copy(variant)));
                        families.add("{\"id\":"+esc(product.id)+",\"name\":"+esc(product.name)+",\"category\":"+esc(product.category)+",\"brand\":"+esc(product.brand)+",\"description\":"+esc(product.description)+",\"active\":"+product.active()+",\"variants\":"+variants+"}");
                    }
                    return families.toString();
                });
                sendText(e,200,"application/json; charset=utf-8",body);return;
            }
            if(path.equals("/v1/web/market-offers") && method.equals("GET") && database!=null) {
                StringJoiner offers=new StringJoiner(",","[","]");
                for(var offer:database.marketOffers()) offers.add("{\"productId\":"+esc(offer.productId())+",\"sku\":"+esc(offer.sourceSku())+",\"url\":"+esc(offer.url())+",\"price\":"+offer.price()+",\"availability\":"+esc(offer.availability())+",\"fetchedAt\":"+esc(offer.fetchedAt())+"}");
                sendText(e,200,"application/json; charset=utf-8",offers.toString()); return;
            }
            if(path.equals("/v1/web/build-check") && method.equals("GET") && database!=null) {
                String checks=withStore(() -> {
                    Set<String> ids=store.cart().keySet();
                    List<ProductVariant> selected=store.products().stream().filter(p->ids.contains(p.id)).toList();
                    StringJoiner json=new StringJoiner(",","[","]");
                    for(var check:BuildChecks.applicable(selected)) json.add("{\"name\":"+esc(check.name())+",\"status\":"+esc(check.status())+",\"detail\":"+esc(check.detail())+"}");
                    return json.toString();
                });
                sendText(e,200,"application/json; charset=utf-8",checks); return;
            }
            if(path.equals("/v1/web/compare-check") && method.equals("GET")) {
                String raw=e.getRequestURI().getRawQuery();
                if(raw==null || !raw.startsWith("ids=") || raw.contains("&") || raw.length()>1000) {
                    sendText(e,400,"text/plain; charset=utf-8","Chọn từ 2 đến 8 sản phẩm."); return;
                }
                String value=java.net.URLDecoder.decode(raw.substring(4),StandardCharsets.UTF_8);
                List<String> ids=Arrays.asList(value.split(",",-1));
                if(ids.size()<2 || ids.size()>8 || new HashSet<>(ids).size()!=ids.size() || ids.stream().anyMatch(id->!id.matches("[A-Za-z0-9_-]+"))) {
                    sendText(e,400,"text/plain; charset=utf-8","Chọn từ 2 đến 8 sản phẩm khác nhau."); return;
                }
                String result=withStore(() -> {
                    List<ProductVariant> selected=new ArrayList<>();
                    for(String id:ids) { ProductVariant product=store.product(id); if(product==null || !product.active) return null; selected.add(product); }
                    StringJoiner pairs=new StringJoiner(",","[","]");
                    for(int i=0;i<selected.size();i++) for(int j=i+1;j<selected.size();j++) {
                        ProductVariant left=selected.get(i),right=selected.get(j); var check=store.assessPair(left.id,right.id);
                        if(check.status()==vn.techzone.application.PairCompatibility.Status.NOT_APPLICABLE)continue;
                        pairs.add("{\"left\":"+esc(left.name)+",\"right\":"+esc(right.name)+",\"status\":"+esc(check.status().name())+",\"approved\":"+store.approvedCompatible(left.id,right.id)+",\"detail\":"+esc(check.detail())+"}");
                    }
                    StringJoiner physical=new StringJoiner(",","[","]");
                    for(var check:BuildChecks.applicable(selected))
                        physical.add("{\"name\":"+esc(check.name())+",\"status\":"+esc(check.status())+",\"detail\":"+esc(check.detail())+"}");
                    return "{\"pairs\":"+pairs+",\"physical\":"+physical+"}";
                });
                if(result==null) { sendText(e,404,"text/plain; charset=utf-8","Không tìm thấy sản phẩm."); return; }
                sendText(e,200,"application/json; charset=utf-8",result); return;
            }
            if(path.equals("/v1/web/market-offers/refresh") && method.equals("POST") && database!=null) {
                if(!marketRefresh.compareAndSet(false,true)) { sendText(e,409,"text/plain; charset=utf-8","Đang cập nhật giá GearVN."); return; }
                try {
                    String result=PythonModels.run("gearvn_offers.py",70,database.databasePath().toString());
                    sendText(e,200,"application/json; charset=utf-8",result); return;
                } finally { marketRefresh.set(false); }
            }
            if(path.equals("/v1/web/catalog/refresh") && method.equals("POST") && database!=null) {
                if(!marketRefresh.compareAndSet(false,true)) { sendText(e,409,"text/plain; charset=utf-8","Đang crawl dữ liệu GearVN."); return; }
                try {
                    Path snapshot=imageDirectory.getParent().resolve("gearvn-catalog.tsv");
                    GearVNCatalogCrawler.crawl(snapshot,200);
                    int count;
                    synchronized(storeLock) {
                        count=database.refreshGearVNCatalog(snapshot);
                        SqliteStore.State current=database.read();
                        TechZoneStore replacement=SqliteStore.decodeSession(current.bytes());
                        TechZoneStore previous=store;
                        store=replacement;
                        previous.closeRepository();
                        clearGearVNImageCache();
                    }
                    sendText(e,200,"application/json; charset=utf-8","{\"updated\":"+count+",\"total\":"+count+",\"errors\":[]}"); return;
                } catch(InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    sendText(e,503,"text/plain; charset=utf-8","Crawl bị gián đoạn; dữ liệu catalogue cũ vẫn được giữ."); return;
                } catch(Exception ex) {
                    String message=Objects.toString(ex.getMessage(),"Không crawl được GearVN.");
                    sendText(e,503,"text/plain; charset=utf-8",message.substring(0,Math.min(400,message.length()))); return;
                } finally { marketRefresh.set(false); }
            }
            if(path.equals("/v1/operations") && method.equals("POST") && database!=null) {
                long expected;
                try { expected=Long.parseLong(e.getRequestHeaders().getFirst("X-TechZone-Revision")); }
                catch(RuntimeException ex) { sendText(e,400,"text/plain; charset=utf-8","Thiếu phiên bản dữ liệu."); return; }
                byte[] request; try(InputStream in=e.getRequestBody()) { request=in.readNBytes(16385); }
                if(request.length>16384) { sendText(e,413,"text/plain; charset=utf-8","Yêu cầu quá lớn."); return; }
                Map<String,String> fields=new HashMap<>(); Role role; String operation;
                try {
                    for(String pair:new String(request,StandardCharsets.UTF_8).split("&")) {
                        String[] parts=pair.split("=",2); String key=java.net.URLDecoder.decode(parts[0],StandardCharsets.UTF_8);
                        String value=java.net.URLDecoder.decode(parts.length==2?parts[1]:"",StandardCharsets.UTF_8);
                        if(value.length()>2000 || fields.putIfAbsent(key,value)!=null) throw new IllegalArgumentException();
                    }
                    role=Role.valueOf(fields.get("role")); operation=fields.get("operation");
                    Set<String> required=switch(operation) {
                        case "allocate" -> Set.of("operation","role","actor","order","variant","serial");
                        case "ship" -> Set.of("operation","role","actor","order","carrier","weight","dimensions");
                        case "milestone" -> Set.of("operation","role","actor","order","callback","milestone","location");
                        case "reconcile" -> Set.of("operation","role","actor","order","callback","verdict","token");
                        case "rma_update" -> Set.of("operation","role","actor","rma","status","assigned","diagnosis","parts","resolution");
                        case "profile_update" -> Set.of("operation","role","actor","name","phone","address");
                        default -> throw new IllegalArgumentException();
                    };
                    if(!fields.keySet().equals(required) || fields.get("actor").isBlank() || fields.get("actor").length()>100) throw new IllegalArgumentException();
                    if("ship".equals(operation)) Integer.parseInt(fields.get("weight"));
                    if("reconcile".equals(operation)) PaymentVerdict.valueOf(fields.get("verdict"));
                } catch(RuntimeException ex) { sendText(e,400,"text/plain; charset=utf-8","Dữ liệu thao tác không hợp lệ."); return; }
                boolean permitted=switch(operation) {
                    case "allocate","ship","milestone" -> Set.of(Role.WAREHOUSE,Role.MANAGER,Role.ADMIN).contains(role);
                    case "reconcile" -> Set.of(Role.MANAGER,Role.ADMIN).contains(role);
                    case "rma_update" -> Set.of(Role.SUPPORT,Role.MANAGER,Role.ADMIN).contains(role);
                    case "profile_update" -> role==Role.CUSTOMER;
                    default -> false;
                };
                if(!permitted) { sendText(e,403,"text/plain; charset=utf-8","Vai trò hiện tại không được phép thực hiện thao tác này."); return; }
                try {
                    OperationData result=withStore(() -> {
                        SqliteStore.State current=database.read();
                        if(current.revision()!=expected) throw new IllegalStateException("Dữ liệu đã thay đổi ở phiên khác; hãy mở lại ứng dụng.");
                        TechZoneStore candidate=SqliteStore.decodeSession(current.bytes());
                        try {
                            String message=switch(operation) {
                                case "allocate" -> new AllocateSerialUseCase(new TechZoneInventoryAdapter(candidate)).execute(fields.get("order"),fields.get("variant"),fields.get("serial"),fields.get("actor"));
                                case "ship" -> candidate.shipOrder(fields.get("order"),fields.get("carrier"),Integer.parseInt(fields.get("weight")),fields.get("dimensions"),fields.get("actor"));
                                case "milestone" -> candidate.recordShipmentMilestone(fields.get("order"),fields.get("callback"),fields.get("milestone"),fields.get("location"),fields.get("actor"));
                                case "reconcile" -> candidate.reconcilePaymentCallback(fields.get("order"),fields.get("callback"),PaymentVerdict.valueOf(fields.get("verdict")),fields.get("token"));
                                case "rma_update" -> candidate.updateRma(fields.get("rma"),fields.get("status"),fields.get("assigned"),fields.get("diagnosis"),fields.get("parts"),fields.get("resolution"),role,fields.get("actor"));
                                case "profile_update" -> candidate.updateCustomerProfile(fields.get("name"),fields.get("phone"),fields.get("address"));
                                default -> throw new IllegalArgumentException();
                            };
                            byte[] updated=SqliteStore.encode(candidate); long next=database.save(candidate,expected);
                            TechZoneStore previous=store; store=candidate; previous.closeRepository();
                            return new OperationData(next,updated,message);
                        } catch(Exception ex) { candidate.closeRepository(); throw ex; }
                    });
                    e.getResponseHeaders().set("X-TechZone-Revision",Long.toString(result.revision()));
                    e.getResponseHeaders().set("X-TechZone-Message",Base64.getUrlEncoder().withoutPadding().encodeToString(result.message().getBytes(StandardCharsets.UTF_8)));
                    respond(e,200,"application/octet-stream",result.state()); return;
                } catch(Exception ex) {
                    Throwable cause=ex; while(cause.getCause()!=null) cause=cause.getCause();
                    if(cause instanceof IllegalStateException) { sendText(e,409,"text/plain; charset=utf-8",cause.getMessage()); return; }
                    throw ex;
                }
            }
            if(path.equals("/v1/web/ai-status") && method.equals("GET")) {
                SemanticSearch engine=semanticSearch;
                String status=engine==null ? "Dịch vụ tìm kiếm AI chưa được bật" : engine.status();
                sendText(e,200,"application/json; charset=utf-8","{\"searchModel\":"+esc(status)+"}"); return;
            }
            if(path.equals("/v1/web/state") && method.equals("GET") && database!=null) {
                String body=withStore(this::webState); sendText(e,200,"application/json; charset=utf-8",body); return;
            }
            if(path.equals("/v1/web/advisor-status") && method.equals("GET")) {
                sendText(e,200,"application/json; charset=utf-8","{\"model\":"+esc(advisorChat.status())+"}"); return;
            }
            if(path.equals("/v1/web/advisor") && method.equals("POST")) {
                Map<String,String> fields=form(e,32768);
                String message=value(fields,"message").trim(), context=value(fields,"context").trim(), conversation=value(fields,"conversation").trim();
                if(message.isBlank() || message.length()>600 || context.length()>1000 || conversation.length()>1200) {
                    sendText(e,400,"text/plain; charset=utf-8","Nhập câu hỏi tối đa 600 ký tự."); return;
                }
                String combined=(context+" "+message).trim();
                SemanticSearch engine=semanticSearch;
                SemanticSnapshot snapshot=withStore(() -> {
                    List<SemanticSearch.Document> documents=new ArrayList<>(store.products().stream()
                            .filter(p -> vn.techzone.application.CatalogSearchService.discoverable(p,store.available(p.id))).map(CatalogBackend::semanticDocument).toList());
                    documents.add(new SemanticSearch.Document("__intent_build__","Tư vấn cấu hình lắp ráp dàn máy tính bàn PC theo nhu cầu làm việc, chơi game, đồ họa và ngân sách."));
                    documents.add(new SemanticSearch.Document("__intent_product__","Tìm mua một sản phẩm hoặc linh kiện điện tử theo yêu cầu của khách hàng."));
                    documents.add(new SemanticSearch.Document("__intent_question__","Giải thích, hướng dẫn và so sánh kiến thức công nghệ máy tính."));
                    return new SemanticSnapshot(store.catalogRevision(),store.searchServiceAvailable(),documents);
                });
                // The first BGE request also indexes the catalogue; a short deadline silently discarded real inference.
                Map<String,Double> scores=engine!=null && snapshot.available() ? engine.rank(combined,snapshot.documents(),30000) : null;
                AdvisorData data=withStore(() -> advisorData(message,context,combined,snapshot,scores));
                AdvisorService.Answer answer=data.answer();
                String reply=answer.reply(), model="Tư vấn theo danh mục";
                boolean general=reply.startsWith("Bạn có thể hỏi");
                String facts=answer.configuration() ? (answer.products().isEmpty()?"Chưa có cấu hình phù hợp hoặc còn thiếu thông tin.":"Hệ thống có gợi ý từ danh mục đã kiểm tra.")
                        : answer.products().isEmpty()?"Hệ thống chưa tìm thấy sản phẩm phù hợp.":"Hệ thống có sản phẩm trong danh mục.";
                String dialogue=general ? conversation : conversation.replaceAll("\\d+(?:[.,]\\d+)*","[đã ghi nhận]");
                String latest=general ? message : message.replaceAll("\\d+(?:[.,]\\d+)*","[đã ghi nhận]");
                String generated=general || answer.configuration() || !answer.products().isEmpty()
                        ? advisorChat.reply("Đối thoại trước: "+dialogue+"\nYêu cầu mới: "+latest+"\nThông tin hệ thống: "+facts,general) : null;
                AdvisorData refreshed=withStore(() -> advisorData(message,context,combined,snapshot,scores));
                if(answer.products().isEmpty()!=refreshed.answer().products().isEmpty()
                        || general!=refreshed.answer().reply().startsWith("Bạn có thể hỏi")) generated=null;
                data=refreshed; answer=data.answer(); reply=answer.reply(); general=reply.startsWith("Bạn có thể hỏi");
                String intro=general?generated:safeShoppingIntro(generated,!answer.products().isEmpty());
                if(intro!=null && !intro.isBlank()) {
                    reply=general ? intro : intro+"\n\n"+reply;
                    model=advisorChat.status()+(data.semanticUsed()?" + "+engine.modelName():"");
                } else if(data.semanticUsed()) model=engine.modelName()+" + danh mục";
                StringJoiner items=new StringJoiner(",","[","]");
                for(ProductData item:data.products()) items.add(json(item));
                boolean rankedBuild=data.semanticUsed() && answer.configuration() && !answer.products().isEmpty();
                String rankingMethod=answer.configuration() && !answer.products().isEmpty() ? (rankedBuild?"semantic":"price") : "none";
                StringJoiner relevance=new StringJoiner(",","{","}");
                if(rankedBuild) for(ProductData item:data.products()) {
                    Double score=scores.get(item.id());
                    if(score!=null && Double.isFinite(score)) relevance.add(esc(item.id())+":"+Double.toString(score));
                }
                String body="{\"reply\":"+esc(reply)+",\"products\":"+items+",\"total\":"+answer.total()
                        +",\"budget\":"+answer.budget()+",\"configuration\":"+answer.configuration()+",\"model\":"+esc(model)
                        +",\"rankingMethod\":"+esc(rankingMethod)+",\"rankingModel\":"+esc(rankedBuild?engine.modelName():"")
                        +",\"semanticScores\":"+relevance+"}";
                sendText(e,200,"application/json; charset=utf-8",body); return;
            }
            if(path.equals("/v1/web/action") && method.equals("POST") && database!=null) {
                Map<String,String> f=form(e);
                try {
                    String result=withStore(() -> {
                        SqliteStore.State current=database.read(); TechZoneStore candidate=SqliteStore.decodeSession(current.bytes());
                        try {
                            String op=value(f,"action"), id=value(f,"id"), actor="Quản trị viên";
                            String message=switch(op) {
                                case "add" -> candidate.addToCart(id,Integer.parseInt(value(f,"quantity")));
                                case "add_recommended" -> candidate.addRecommendedToCart(value(f,"primary"),id,1);
                                case "quantity" -> candidate.updateCartQuantity(id,Integer.parseInt(value(f,"quantity")));
                                case "checkout" -> new CheckoutUseCase(new TechZoneOrderAdapter(candidate)).execute(value(f,"address"),value(f,"payment"),value(f,"promotion"),PaymentVerdict.valueOf(value(f,"verdict")),value(f,"idempotency")).message();
                                case "allocate" -> new AllocateSerialUseCase(new TechZoneInventoryAdapter(candidate)).execute(value(f,"order"),id,value(f,"serial"),actor);
                                case "ship" -> candidate.shipOrder(value(f,"order"),value(f,"carrier"),Integer.parseInt(value(f,"weight")),value(f,"dimensions"),actor);
                                case "milestone" -> candidate.recordShipmentMilestone(value(f,"order"),UUID.randomUUID().toString(),value(f,"milestone"),value(f,"location"),actor);
                                case "restock_request" -> candidate.createRestockRequest(id,Integer.parseInt(value(f,"quantity")),actor);
                                case "receive" -> candidate.receiveRestockRequest(id,actor);
                                case "adjust" -> candidate.adjustInventory(id,Integer.parseInt(value(f,"quantity")),value(f,"reason"),actor);
                                case "rma" -> new WarrantyUseCase(new TechZoneWarrantyAdapter(candidate)).createRma(value(f,"serial"),value(f,"problem"),Role.ADMIN);
                                case "repair" -> new WarrantyUseCase(new TechZoneWarrantyAdapter(candidate)).requestRepair(value(f,"serial"),value(f,"problem"),Role.ADMIN,actor);
                                case "reconcile" -> candidate.reconcilePending(value(f,"order"),PaymentVerdict.valueOf(value(f,"verdict")));
                                case "note" -> candidate.addConsultationNote(value(f,"order"),value(f,"note"),actor);
                                case "verify" -> candidate.verifySalesOrder(value(f,"order"),Role.ADMIN,actor);
                                case "rma_update" -> candidate.updateRma(id,value(f,"status"),value(f,"assigned"),value(f,"diagnosis"),value(f,"parts"),value(f,"resolution"),Role.ADMIN,actor);
                                case "review" -> candidate.addReview(id,value(f,"text"),Integer.parseInt(value(f,"stars")));
                                case "consent" -> { candidate.setInteractionConsent(Boolean.parseBoolean(value(f,"enabled"))); yield "Đã cập nhật quyền riêng tư."; }
                                case "track_search" -> { List<ProductVariant> items=Arrays.stream(value(f,"ids").split(",")).map(candidate::product).filter(Objects::nonNull).toList(); candidate.recordSearch(value(f,"query"),value(f,"filters"),items,value(f,"strategy"),0); yield "Đã ghi nhận tìm kiếm."; }
                                case "track_view" -> { candidate.recordSearchClick(id); candidate.recordProductView(id); yield "Đã ghi nhận lượt xem."; }
                                case "track_recs" -> {
                                    Set<String> eligibleIds=candidate.eligibleRecommendations(id,candidate.cart().keySet()).stream().map(v->v.id).collect(java.util.stream.Collectors.toSet());
                                    List<ProductVariant> shown=Arrays.stream(value(f,"ids").split(",")).distinct().map(candidate::product).filter(Objects::nonNull)
                                            .filter(v -> eligibleIds.contains(v.id)).limit(candidate.recommendationLimit()).toList();
                                    candidate.recordRecommendationResult(id,new TechZoneStore.RecommendationResult(shown,value(f,"strategy"),0,0));
                                    yield "Đã ghi nhận gợi ý.";
                                }
                                case "track_rec_click" -> { candidate.recordRecommendationClick(value(f,"primary"),id); yield "Đã ghi nhận lựa chọn."; }
                                case "threshold" -> { candidate.setThreshold(id,Integer.parseInt(value(f,"quantity")),actor); yield "Đã cập nhật ngưỡng tồn."; }
                                case "supplier" -> candidate.syncSupplierCatalog(id,value(f,"key"),value(f,"value"),Integer.parseInt(value(f,"days")),actor);
                                case "supplier_return" -> Boolean.parseBoolean(value(f,"approve")) ? candidate.confirmSupplierReturn(id,Role.ADMIN,actor) : candidate.cancelSupplierReturn(id,Role.ADMIN,actor);
                                case "verification" -> candidate.requestManualWarrantyVerification(value(f,"serial"),value(f,"problem"));
                                case "warranty_duration" -> candidate.setWarrantyDuration(id,Integer.parseInt(value(f,"quantity")),value(f,"reason"),actor);
                                case "verification_review" -> new WarrantyUseCase(new TechZoneWarrantyAdapter(candidate)).reviewVerification(id,Role.ADMIN,actor);
                                case "flag" -> candidate.flagCompatibility(value(f,"primary"),id,value(f,"reason"),Role.ADMIN,actor);
                                case "review_compatibility" -> candidate.reviewCompatibility(id,Boolean.parseBoolean(value(f,"approve")),Role.ADMIN,actor,value(f,"reason"));
                                case "role" -> candidate.assignDemoRole(value(f,"email"),Role.valueOf(value(f,"role")),actor);
                                case "limit" -> { candidate.setRecommendationLimit(Integer.parseInt(value(f,"quantity")),actor); yield "Đã cập nhật số gợi ý."; }
                                case "index" -> candidate.refreshSearchIndexes(actor);
                                case "evaluate_search" -> { var m=candidate.runOfflineAiEvaluation(); yield String.format(Locale.ROOT,"Đánh giá TF-IDF mẫu: %d truy vấn; Recall@3 %.1f%%; NDCG@3 %.1f%%; lỗi tương thích %d/%d. Không đại diện mô hình ngữ nghĩa đang chạy hoặc production.",m.labelledQueries(),100*m.semanticRecallAt3(),100*m.semanticNdcgAt3(),m.compatibilityViolations(),m.recommendationChecks()); }
                                case "evaluate_rec" -> { var m=candidate.runRecommendationEvaluation(); yield String.format(Locale.ROOT,"Đánh giá gợi ý mẫu: %d trường hợp; Recall@%d %.1f%%; NDCG %.1f%%; độ phủ %.1f%%; lỗi tương thích %d/%d. Không phải SLA.",m.cases(),m.k(),100*m.recallAtK(),100*m.ndcgAtK(),100*m.coverage(),m.violations(),m.checkedItems()); }
                                case "notifications_read" -> { candidate.markNotificationsRead(Role.ADMIN); yield "Đã đánh dấu thông báo."; }
                                case "spec" -> candidate.updateTechnicalSpec(id,value(f,"key"),value(f,"value"),actor);
                                case "register" -> candidate.registerCustomer(value(f,"name"),value(f,"email"),value(f,"password").toCharArray()).message();
                                case "login" -> candidate.authenticateCustomer(value(f,"email"),value(f,"password").toCharArray()).message();
                                default -> throw new IllegalArgumentException("Thao tác không được hỗ trợ.");
                            };
                            database.save(candidate,current.revision()); TechZoneStore previous=store; store=candidate; previous.closeRepository(); return message;
                        } catch(Exception ex) { candidate.closeRepository(); throw ex; }
                    });
                    sendText(e,200,"application/json; charset=utf-8","{\"message\":"+esc(result)+"}"); return;
                } catch(Exception ex) { Throwable cause=ex; while(cause.getCause()!=null) cause=cause.getCause(); sendText(e,400,"text/plain; charset=utf-8",cause.getMessage()==null?"Dữ liệu không hợp lệ.":cause.getMessage()); return; }
            }
            if(path.equals("/v1/web/transcribe") && method.equals("POST")) {
                byte[] audio; try(InputStream in=e.getRequestBody()) { audio=in.readNBytes(1024*1024+1); }
                if(audio.length==0 || audio.length>1024*1024) { sendText(e,400,"text/plain; charset=utf-8","Bản ghi âm không hợp lệ."); return; }
                Path wav=Files.createTempFile("techzone-voice-", ".wav");
                try {
                    Files.write(wav,audio); String result=PythonModels.run("transcribe.py",180,wav.toString());
                    e.getResponseHeaders().set("X-TechZone-Speech",modelReady("whisper-medium","abdf7c39ab9d0397620ccaea8974cc764cd0953e","model.safetensors")?"Whisper Medium":modelReady("whisper-small","973afd24965f72e36ca33b3055d56a652f456b4d","model.safetensors")?"Whisper Small":"Whisper Tiny");
                    sendText(e,200,"text/plain; charset=utf-8",result.replaceFirst("^vi\\t",""));
                }
                finally { Files.deleteIfExists(wav); } return;
            }
            if (path.equals("/v1/search") && method.equals("GET")) {
                String raw=e.getRequestURI().getRawQuery();
                if(raw==null) raw="";
                if(raw.length()>8192) { sendText(e,414,"text/plain; charset=utf-8","Truy vấn quá dài."); return; }
                Map<String,String> params=new LinkedHashMap<>(), specs=new LinkedHashMap<>();
                try {
                    if(!raw.isBlank()) for(String pair:raw.split("&")) {
                        String[] parts=pair.split("=",2);
                        String key=java.net.URLDecoder.decode(parts[0],StandardCharsets.UTF_8);
                        String value=java.net.URLDecoder.decode(parts.length==2?parts[1]:"",StandardCharsets.UTF_8);
                        Map<String,String> target=key.startsWith("spec.")?specs:params;
                        if(key.startsWith("spec.")) key=key.substring(5);
                        if(key.isBlank() || value.length()>1000 || target.putIfAbsent(key,value)!=null) throw new IllegalArgumentException();
                    }
                    if(!Set.of("query","category","socket","memory","maxPrice","semantic").containsAll(params.keySet())) throw new IllegalArgumentException();
                    if(specs.size()>32 || specs.keySet().stream().anyMatch(k -> !k.matches("[A-Za-z][A-Za-z0-9_]{0,39}"))) throw new IllegalArgumentException();
                    long price=Long.parseLong(params.getOrDefault("maxPrice","0"));
                    if(price<0 || !Set.of("true","false").contains(params.getOrDefault("semantic","true"))) throw new IllegalArgumentException();
                } catch(RuntimeException ex) { sendText(e,400,"text/plain; charset=utf-8","Bộ lọc tìm kiếm không hợp lệ."); return; }
                long price=Long.parseLong(params.getOrDefault("maxPrice","0"));
                boolean semantic=Boolean.parseBoolean(params.getOrDefault("semantic","true"));
                String query=params.getOrDefault("query","");
                SemanticSearch engine=semanticSearch;
                SemanticSnapshot snapshot=null; Map<String,Double> scores=null;
                if(engine!=null && semantic && !query.isBlank()) {
                    snapshot=withStore(() -> new SemanticSnapshot(store.catalogRevision(),store.searchServiceAvailable(),
                            store.products().stream().filter(p -> p.active).map(CatalogBackend::semanticDocument).toList()));
                    if(snapshot.available()) scores=engine.rank(query,snapshot.documents());
                }
                SemanticSnapshot captured=snapshot; Map<String,Double> ranked=scores;
                String[] result=withStore(() -> {
                    boolean useEmbeddings=ranked!=null && captured!=null && captured.revision()==store.catalogRevision() && store.searchServiceAvailable();
                    boolean needsFallback=engine!=null && semantic && !query.isBlank() && !useEmbeddings;
                    var found=new SearchUseCase(new TechZoneCatalogAdapter(store)).execute(query,params.getOrDefault("category","Tất cả"),params.getOrDefault("socket",""),params.getOrDefault("memory",""),price,specs,semantic,needsFallback,useEmbeddings?ranked:null,useEmbeddings?engine.modelName():"");
                    return new String[]{found.items().stream().map(p -> p.id).collect(java.util.stream.Collectors.joining("\n")),found.strategy()};
                });
                e.getResponseHeaders().set("X-TechZone-Strategy",Base64.getUrlEncoder().withoutPadding().encodeToString(result[1].getBytes(StandardCharsets.UTF_8)));
                sendText(e,200,"text/plain; charset=utf-8",result[0]); return;
            }
            if (path.equals("/v1/checkout") && method.equals("POST") && database != null) {
                long expected;
                try { expected=Long.parseLong(e.getRequestHeaders().getFirst("X-TechZone-Revision")); }
                catch (RuntimeException ex) { sendText(e,400,"text/plain; charset=utf-8","Thiếu phiên bản dữ liệu."); return; }
                byte[] request;
                try(InputStream in=e.getRequestBody()) { request=in.readNBytes(16385); }
                if(request.length>16384) { sendText(e,413,"text/plain; charset=utf-8","Yêu cầu quá lớn."); return; }
                Map<String,String> fields = new HashMap<>();
                try {
                    for(String pair:new String(request,StandardCharsets.UTF_8).split("&")) {
                        String[] parts=pair.split("=",2);
                        String key=java.net.URLDecoder.decode(parts[0],StandardCharsets.UTF_8);
                        String value=java.net.URLDecoder.decode(parts.length==2?parts[1]:"",StandardCharsets.UTF_8);
                        if(fields.putIfAbsent(key,value)!=null) throw new IllegalArgumentException();
                    }
                    if(!fields.keySet().equals(Set.of("address","payment","promotion","verdict","idempotency"))
                            || fields.get("address").length()>1000 || fields.get("payment").length()>100
                            || fields.get("promotion").length()>100 || fields.get("idempotency").length()>140)
                        throw new IllegalArgumentException();
                    PaymentVerdict.valueOf(fields.get("verdict"));
                } catch (RuntimeException ex) { sendText(e,400,"text/plain; charset=utf-8","Dữ liệu thanh toán không hợp lệ."); return; }
                try {
                    CheckoutData result=withStore(() -> {
                        SqliteStore.State current=database.read();
                        if(current.revision()!=expected) throw new IllegalStateException("Dữ liệu đã thay đổi ở phiên khác; hãy mở lại ứng dụng.");
                        TechZoneStore candidate=SqliteStore.decodeSession(current.bytes());
                        try {
                            var outcome=new CheckoutUseCase(new TechZoneOrderAdapter(candidate)).execute(fields.get("address"),fields.get("payment"),fields.get("promotion"),
                                    PaymentVerdict.valueOf(fields.get("verdict")),fields.get("idempotency"));
                            byte[] updated=SqliteStore.encode(candidate);
                            long next=database.save(candidate,expected);
                            TechZoneStore previous=store; store=candidate; previous.closeRepository();
                            return new CheckoutData(next,updated,outcome.message(),outcome.order()==null?"":outcome.order().id,outcome.confirmed());
                        } catch(Exception ex) { candidate.closeRepository(); throw ex; }
                    });
                    e.getResponseHeaders().set("X-TechZone-Revision",Long.toString(result.revision()));
                    e.getResponseHeaders().set("X-TechZone-Message",Base64.getUrlEncoder().withoutPadding().encodeToString(result.message().getBytes(StandardCharsets.UTF_8)));
                    e.getResponseHeaders().set("X-TechZone-Order",result.orderId());
                    e.getResponseHeaders().set("X-TechZone-Confirmed",Boolean.toString(result.confirmed()));
                    respond(e,200,"application/octet-stream",result.state()); return;
                } catch(Exception ex) {
                    Throwable cause=ex; while(cause.getCause()!=null) cause=cause.getCause();
                    if(cause instanceof IllegalStateException) { sendText(e,409,"text/plain; charset=utf-8",cause.getMessage()); return; }
                    throw ex;
                }
            }
            if (path.equals("/v1/inventory") && method.equals("GET")) {
                String body=withStore(() -> {
                    StringJoiner rows=new StringJoiner(",","[","]");
                    for(ProductVariant p:store.products()) {
                        InventoryBalance b=store.balance(p.id);
                        rows.add("{\"productId\":"+esc(p.id)+",\"sku\":"+esc(p.sku)+",\"available\":"+b.available
                                +",\"reserved\":"+b.reserved+",\"allocated\":"+b.allocated+",\"threshold\":"+b.reorderThreshold+"}");
                    } return rows.toString();
                });
                sendText(e,200,"application/json; charset=utf-8",body); return;
            }
            if (path.equals("/v1/orders") && method.equals("GET")) {
                String body=withStore(() -> {
                    StringJoiner rows=new StringJoiner(",","[","]");
                    for(SalesOrder o:store.orders()) rows.add("{\"id\":"+esc(o.id)+",\"customerId\":"+esc(o.customerId)+",\"number\":"+esc(o.number)
                            +",\"status\":"+esc(o.status.name())+",\"total\":"+o.total+",\"createdAt\":"+esc(o.createdAt.toString())+"}");
                    return rows.toString();
                });
                sendText(e,200,"application/json; charset=utf-8",body); return;
            }
            if (path.equals("/v1/session/state") && database != null) {
                if (method.equals("GET")) {
                    SqliteStore.State state = database.read();
                    e.getResponseHeaders().set("X-TechZone-Revision", Long.toString(state.revision()));
                    respond(e,200,"application/octet-stream",state.bytes()); return;
                }
                if (method.equals("PUT")) {
                    long revision;
                    try { revision = Long.parseLong(e.getRequestHeaders().getFirst("X-TechZone-Revision")); }
                    catch (RuntimeException ex) { sendText(e,400,"text/plain; charset=utf-8","Thiếu phiên bản dữ liệu."); return; }
                    byte[] bytes;
                    try (InputStream in=e.getRequestBody()) { bytes=in.readNBytes(64 * 1024 * 1024 + 1); }
                    if (bytes.length == 0 || bytes.length > 64 * 1024 * 1024) { sendText(e,400,"text/plain; charset=utf-8","Dữ liệu không hợp lệ."); return; }
                    TechZoneStore incoming = SqliteStore.decodeSession(bytes);
                    try {
                        long next = withStore(() -> { long n=database.save(incoming, revision); TechZoneStore previous=store; store=incoming; previous.closeRepository(); return n; });
                        e.getResponseHeaders().set("X-TechZone-Revision", Long.toString(next));
                        sendText(e,200,"text/plain; charset=utf-8","Đã lưu."); return;
                    } catch (Exception ex) {
                        incoming.closeRepository();
                        Throwable cause=ex; while(cause.getCause()!=null) cause=cause.getCause();
                        if(cause instanceof IllegalStateException) { sendText(e,409,"text/plain; charset=utf-8",cause.getMessage()); return; }
                        throw ex;
                    }
                }
            }
            if (path.equals("/v1/products") && method.equals("GET")) {
                List<ProductData> products=withStore(() -> store.products().stream().map(this::copy).toList());
                StringJoiner j=new StringJoiner(",","[","]"); for(ProductData p:products)j.add(json(p)); sendText(e,200,"application/json; charset=utf-8",j.toString()); return;
            }
            if (path.equals("/v1/search/images") && method.equals("POST")) {
                BufferedImage query=ImageNormalizer.normalize(readImage(e));
                List<ProductData> products=withStore(() -> store.products().stream().map(this::copy).toList());
                prefetchSourceImages(products);
                Set<String> eligible=new HashSet<>(); for(ProductData p:products) if(p.active && (p.available>0 || "GEARVN".equals(p.specs().get("sourceProvider"))) && index.contains(p.id))eligible.add(p.id);
                long total=products.stream().filter(p -> p.active && (p.available>0 || "GEARVN".equals(p.specs().get("sourceProvider"))) && p.imageAvailable()).count();
                e.getResponseHeaders().set("X-TechZone-Image-Indexed",Integer.toString(eligible.size()));
                e.getResponseHeaders().set("X-TechZone-Image-Total",Long.toString(total));
                if (eligible.isEmpty()) { sendText(e,200,"text/plain; charset=utf-8",""); return; }
                Path queryFile=Files.createTempFile("techzone-image-query-", ".png");
                Path eligibleFile=Files.createTempFile("techzone-image-eligible-", ".txt");
                String encoded;
                try {
                    if(!ImageIO.write(query,"png",queryFile.toFile())) throw new IOException("Không xử lý được ảnh.");
                    Files.write(eligibleFile, eligible, StandardCharsets.UTF_8);
                    try { encoded=PythonModels.run("image_search.py",180,queryFile.toString(),imageDirectory.toString(),eligibleFile.toString()); }
                    catch(IOException ex) { sendText(e,503,"text/plain; charset=utf-8",ex.getMessage()); return; }
                } finally { Files.deleteIfExists(queryFile); Files.deleteIfExists(eligibleFile); }
                StringBuilder out=new StringBuilder();
                String visionModel=encoded.startsWith("#model\tSigLIP 2") ? "SigLIP 2" : encoded.startsWith("#model\tSigLIP") ? "SigLIP" : "ResNet-18";
                e.getResponseHeaders().set("X-TechZone-Vision",visionModel);
                for(String line:encoded.split("\\R")) {
                    String[] fields=line.split("\\t",2); if(fields.length!=2 || !eligible.contains(fields[0]))continue;
                    try { double score=Double.parseDouble(fields[1]); if(Double.isFinite(score) && score>=0.70) out.append(fields[0]).append('\t').append(fields[1]).append('\n'); }
                    catch(NumberFormatException ignored) { }
                }
                sendText(e,200,"text/plain; charset=utf-8",out.toString()); return;
            }
            String prefix="/v1/products/";
            if (path.startsWith(prefix)) {
                String tail=path.substring(prefix.length()); boolean image=tail.endsWith("/image"), details=tail.endsWith("/details"), recommendations=tail.endsWith("/recommendations"), similar=tail.endsWith("/similar"), candidates=tail.endsWith("/compatibility-candidates");
                String id=image?tail.substring(0,tail.length()-6):details?tail.substring(0,tail.length()-8):recommendations?tail.substring(0,tail.length()-16):similar?tail.substring(0,tail.length()-8):candidates?tail.substring(0,tail.length()-"/compatibility-candidates".length()):tail;
                if (!id.matches("[A-Za-z0-9_-]+")) { sendText(e,400,"text/plain; charset=utf-8","Mã sản phẩm không hợp lệ."); return; }
                ProductData p=withStore(() -> { ProductVariant v=store.product(id); return v==null ? null : copy(v); });
                if(p==null) { sendText(e,404,"text/plain; charset=utf-8","Không tìm thấy sản phẩm."); return; }
                if(candidates && method.equals("GET")) {
                    SemanticSearch engine=semanticSearch;
                    ProvisionalCompatibilityService service=new ProvisionalCompatibilityService();
                    CandidateSnapshot snapshot=withStore(() -> {
                        ProductVariant primary=store.product(id);
                        var options=service.candidates(primary,store.products(),store.cart().keySet(),store::available,store::assessPair);
                        String query=primary==null?"":primary.category+" "+primary.name+" linh kiện máy tính lắp ráp";
                        return new CandidateSnapshot(store.catalogRevision(),store.recommendationServiceAvailable(),query,
                                options.stream().map(c->semanticDocument(c.product())).toList());
                    });
                    Map<String,Double> scores=engine!=null && snapshot.available() && !snapshot.documents().isEmpty()
                            ? engine.rank(snapshot.query(),snapshot.documents(),1000) : null;
                    String body=withStore(() -> {
                        ProductVariant primary=store.product(id);
                        var options=service.candidates(primary,store.products(),store.cart().keySet(),store::available,store::assessPair);
                        boolean ranked=scores!=null && snapshot.revision()==store.catalogRevision();
                        Set<String> allowed=snapshot.documents().stream().map(SemanticSearch.Document::id).collect(java.util.stream.Collectors.toSet());
                        List<ProvisionalCompatibilityService.Candidate> sorted=options.stream().filter(c->allowed.contains(c.product().id))
                                .sorted(Comparator.comparingInt((ProvisionalCompatibilityService.Candidate c)->c.status().equals("PARTIAL")?0:1)
                                        .thenComparing(Comparator.comparingDouble((ProvisionalCompatibilityService.Candidate c)->ranked?scores.getOrDefault(c.product().id,-1.0):0.0).reversed())
                                        .thenComparing(c->c.product().name)).toList();
                        int limit=store.recommendationLimit();List<ProvisionalCompatibilityService.Candidate> chosen=new ArrayList<>();Set<String> categories=new HashSet<>();
                        for(var option:sorted) if(chosen.size()<limit && categories.add(option.product().category)) chosen.add(option);
                        for(var option:sorted) if(chosen.size()<limit && !chosen.contains(option)) chosen.add(option);
                        StringJoiner items=new StringJoiner(",","[","]");
                        for(var option:chosen) items.add("{\"product\":"+json(copy(option.product()))+",\"status\":"+esc(option.status())+",\"reason\":"+esc(option.reason())+"}");
                        return "{\"ranked\":"+ranked+",\"model\":"+esc(ranked?engine.modelName():"")+",\"items\":"+items+"}";
                    });
                    sendText(e,200,"application/json; charset=utf-8",body); return;
                }
                if(similar && method.equals("GET")) {
                    SemanticSearch engine=semanticSearch;
                    SemanticSnapshot snapshot=withStore(() -> {
                        ProductVariant primary=store.product(id);
                        List<SemanticSearch.Document> docs=new ArrayList<>();
                        if(primary!=null && primary.active) {
                            docs.add(semanticDocument(primary));
                            store.products().stream().filter(v->v.active && !v.id.equals(id)
                                    && v.category.equals(primary.category))
                                    .map(CatalogBackend::semanticDocument).forEach(docs::add);
                        }
                        return new SemanticSnapshot(store.catalogRevision(),store.recommendationServiceAvailable(),docs);
                    });
                    String primary=snapshot.documents().stream().filter(d->d.id().equals(id)).map(SemanticSearch.Document::text).findFirst().orElse("");
                    Map<String,Double> scores=engine!=null && snapshot.available() && snapshot.documents().size()>1
                            ? engine.rank(primary,snapshot.documents(),1000) : null;
                    boolean[] ranked={false};
                    List<ProductData> choices=withStore(() -> {
                        ProductVariant current=store.product(id);
                        if(current==null || !current.active) return List.of();
                        ranked[0]=scores!=null && snapshot.revision()==store.catalogRevision();
                        return store.products().stream().filter(v->!v.id.equals(id) && v.active && v.category.equals(current.category))
                                .sorted(Comparator.comparingDouble((ProductVariant v)->ranked[0]
                                        ? scores.getOrDefault(v.id,-1.0) : sameCategoryScore(current,v)).reversed()
                                        .thenComparing(v->v.name).thenComparing(v->v.id))
                                .map(this::copy).toList();
                    });
                    StringJoiner items=new StringJoiner(",","[","]"); for(ProductData item:choices) items.add(json(item));
                    sendText(e,200,"application/json; charset=utf-8","{\"ready\":"+ranked[0]+",\"model\":"+esc(ranked[0]?engine.modelName():"")+",\"total\":"+choices.size()+",\"items\":"+items+"}"); return;
                }
                if (recommendations && method.equals("GET")) {
                    SemanticSearch engine=semanticSearch;
                    SemanticSnapshot snapshot=withStore(() -> {
                        List<ProductVariant> eligible=new RecommendUseCase(new TechZoneRankingAdapter(store)).eligible(id,store.cart().keySet());
                        List<SemanticSearch.Document> docs=new ArrayList<>();
                        ProductVariant primary=store.product(id);
                        if(primary!=null && primary.active && !eligible.isEmpty()) {
                            docs.add(semanticDocument(primary));
                            eligible.stream().map(CatalogBackend::semanticDocument).forEach(docs::add);
                        }
                        return new SemanticSnapshot(store.catalogRevision(),store.recommendationServiceAvailable(),docs);
                    });
                    String primaryText=snapshot.documents().stream().filter(d -> d.id().equals(id)).map(SemanticSearch.Document::text).findFirst().orElse("");
                    long semanticStarted=System.nanoTime();
                    // Bound synchronous model waiting; the pending inference can refine a later request.
                    Map<String,Double> scores=engine!=null && snapshot.available() && !primaryText.isBlank() ? engine.rank(primaryText,snapshot.documents(),100) : null;
                    double semanticMillis=(System.nanoTime()-semanticStarted)/1_000_000.0;
                    RecommendationData data = withStore(() -> {
                        Map<String,Double> safeScores=snapshot.revision()==store.catalogRevision() ? scores : null;
                        var base = new RecommendUseCase(new TechZoneRankingAdapter(store)).execute(id, store.cart().keySet(), store.recommendationLimit(),safeScores,engine==null?"":engine.modelName());
                        boolean usesSemantic=safeScores!=null && base.strategy().startsWith("AI gợi ý");
                        TechZoneStore.RecommendationResult result=new TechZoneStore.RecommendationResult(base.items(),base.strategy(),
                                base.inferenceMs()+(usesSemantic?semanticMillis:0),base.totalMs()+semanticMillis);
                        List<ProductData> items = result.items().stream().map(this::copy).toList();
                        List<String> reasons = result.items().stream().map(item -> store.recommendationCompatibilityReason(id, item.id)).toList();
                        return new RecommendationData(result, items, reasons);
                    });
                    StringJoiner items = new StringJoiner(",", "[", "]");
                    for (int i = 0; i < data.products.size(); i++) items.add("{\"rank\":" + (i + 1) + ",\"product\":"
                            + json(data.products.get(i)) + ",\"compatibility\":" + esc(data.reasons.get(i)) + "}");
                    String body = "{\"primaryId\":" + esc(id) + ",\"strategy\":" + esc(data.result.strategy())
                            + ",\"inferenceMs\":" + data.result.inferenceMs() + ",\"totalMs\":" + data.result.totalMs()
                            + ",\"items\":" + items + "}";
                    sendText(e,200,"application/json; charset=utf-8",body); return;
                }
                if (details && method.equals("GET")) { sendText(e,200,"text/plain; charset=utf-8",details(p)); return; }
                if (!image && !details && !recommendations && !similar && !candidates && method.equals("GET")) { sendText(e,200,"application/json; charset=utf-8",json(p)); return; }
                Path file=imageDirectory.resolve(id+".png");
                if(image && method.equals("GET")) {
                    if(!Files.isRegularFile(file) && "GEARVN".equals(p.specs().get("sourceProvider"))) cacheSourceImage(p,file);
                    if(!Files.isRegularFile(file)) {
                        String source=p.specs().getOrDefault("sourceImageUrl","");
                        if("GEARVN".equals(p.specs().get("sourceProvider")) && source.matches("https://cdn\\.hstatic\\.net/[^\\s]{1,1000}")) {
                            e.getResponseHeaders().set("Location",source);e.sendResponseHeaders(302,-1);return;
                        }
                        sendText(e,404,"text/plain; charset=utf-8","Image not found."); return;
                    }
                    respond(e,200,"image/png",Files.readAllBytes(file)); return;
                }
                if(image && method.equals("PUT")) {
                    BufferedImage normalized=ImageNormalizer.normalize(readImage(e));
                    Path temp=Files.createTempFile(imageDirectory,".photo-",".png");
                    try { if(!ImageIO.write(normalized,"png",temp.toFile()))throw new IOException("Không lưu được ảnh.");
                        try { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); } catch (AtomicMoveNotSupportedException ex) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
                        index.add(id);
                    } finally { Files.deleteIfExists(temp); }
                    sendText(e,200,"text/plain; charset=utf-8","Đã lưu ảnh."); return;
                }
            }
            sendText(e,404,"text/plain; charset=utf-8","Không tìm thấy đường dẫn.");
        } catch (IOException ex) { sendText(e,400,"text/plain; charset=utf-8",ex.getMessage()==null?"Ảnh không hợp lệ.":ex.getMessage()); }
        catch (Exception ex) { sendText(e,500,"text/plain; charset=utf-8","Không xử lý được yêu cầu."); }
        finally { e.close(); }
    }
    private void web(HttpExchange e) throws IOException {
        try {
            String host=e.getRequestHeaders().getFirst("Host");
            if(host==null || !host.equals("127.0.0.1:"+server.getAddress().getPort())) { sendText(e,403,"text/plain; charset=utf-8","Địa chỉ không hợp lệ."); return; }
            String path=e.getRequestURI().getPath();
            if(!e.getRequestMethod().equals("GET")) { sendText(e,405,"text/plain; charset=utf-8","Không hỗ trợ."); return; }
            String resource=switch(path) { case "/" -> "index.html"; case "/app.js" -> "app.js"; case "/extra.js" -> "extra.js"; case "/advisor.js" -> "advisor.js"; case "/style.css" -> "style.css"; case "/vendor/bootstrap.min.css" -> "vendor/bootstrap.min.css"; case "/vendor/bootstrap.bundle.min.js" -> "vendor/bootstrap.bundle.min.js"; default -> null; };
            if(resource==null) { sendText(e,404,"text/plain; charset=utf-8","Không tìm thấy."); return; }
            try(InputStream in=CatalogBackend.class.getResourceAsStream("/web/"+resource)) {
                if(in==null) { sendText(e,404,"text/plain; charset=utf-8","Thiếu tệp giao diện web."); return; }
                String content=new String(in.readAllBytes(),StandardCharsets.UTF_8);
                if(resource.equals("index.html")) content=content.replace("__TOKEN__",token);
                e.getResponseHeaders().set("X-Content-Type-Options","nosniff");
                e.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; img-src 'self' https://cdn.hstatic.net blob:; script-src 'self'; style-src 'self'; connect-src 'self'");
                sendText(e,200,resource.endsWith(".html")?"text/html; charset=utf-8":resource.endsWith(".js")?"text/javascript; charset=utf-8":"text/css; charset=utf-8",content);
            }
        } finally { e.close(); }
    }
    private static Map<String,String> form(HttpExchange e) throws IOException {
        return form(e,16384);
    }
    private static Map<String,String> form(HttpExchange e,int limit) throws IOException {
        byte[] body=e.getRequestBody().readNBytes(limit+1); if(body.length>limit) throw new IOException("Yêu cầu quá lớn.");
        Map<String,String> result=new HashMap<>();
        for(String pair:new String(body,StandardCharsets.UTF_8).split("&")) {
            String[] parts=pair.split("=",2); String key=java.net.URLDecoder.decode(parts[0],StandardCharsets.UTF_8);
            String value=java.net.URLDecoder.decode(parts.length==2?parts[1]:"",StandardCharsets.UTF_8);
            if(value.length()>2000 || result.putIfAbsent(key,value)!=null) throw new IOException("Dữ liệu không hợp lệ.");
        } return result;
    }
    private static String value(Map<String,String> f,String key) { return f.getOrDefault(key,""); }
    private static boolean modelReady(String directory,String revision,String weight) {
        try {
            Path root=Path.of("models",directory);
            return Files.isRegularFile(root.resolve(weight)) && Files.readString(root.resolve(".techzone-ready"),StandardCharsets.US_ASCII).trim().equals(revision);
        } catch(IOException ex) { return false; }
    }
    private String webState() {
        StringJoiner cart=new StringJoiner(",","[","]");
        for(var item:store.cart().values()) { var p=store.product(item.variantId); if(p!=null) cart.add("{\"id\":"+esc(p.id)+",\"name\":"+esc(p.name)+",\"quantity\":"+item.quantity+",\"price\":"+p.effectivePrice()+"}"); }
        StringJoiner orders=new StringJoiner(",","[","]");
        for(var o:store.orders()) { StringJoiner lines=new StringJoiner(",","[","]");
            for(var l:o.lines) lines.add("{\"id\":"+esc(l.variantId)+",\"quantity\":"+l.quantity+",\"allocated\":"+l.allocatedQuantity+",\"serials\":"+esc(String.join(", ",l.serials))+",\"reservedSample\":"+esc(store.sampleReservedSerial(l.variantId,o.id))+"}");
            StringJoiner journey=new StringJoiner(",","[","]"); for(var v:store.shipmentEvents(o.id)) journey.add("{\"milestone\":"+esc(v.milestone)+",\"location\":"+esc(v.location)+",\"time\":"+esc(v.occurredAt.toString())+"}");
            StringJoiner notes=new StringJoiner(",","[","]"); for(var v:store.consultationNotes(o.id)) notes.add("{\"author\":"+esc(v.author)+",\"note\":"+esc(v.note)+"}");
            orders.add("{\"journey\":"+journey+",\"notes\":"+notes+",\"id\":"+esc(o.id)+",\"customerId\":"+esc(o.customerId)+",\"number\":"+esc(o.number)+",\"status\":"+esc(TechZoneStore.localizedStatus(o.status))+",\"rawStatus\":"+esc(o.status.name())+",\"total\":"+o.total+",\"address\":"+esc(o.shippingAddress)+",\"tracking\":"+esc(o.trackingCode)+",\"lines\":"+lines+"}"); }
        StringJoiner categories=new StringJoiner(",","[","]"); for(var c:store.categoryReports()) categories.add("{\"name\":"+esc(c.category())+",\"sales\":"+c.grossSales()+",\"units\":"+c.units()+",\"available\":"+c.available()+",\"turnover\":"+c.sessionTurnover()+"}");
        StringJoiner reviews=new StringJoiner(",","[","]"); for(var r:store.reviews()) { StringJoiner row=new StringJoiner(",","[","]"); for(String v:r) row.add(esc(v)); reviews.add(row.toString()); }
        StringJoiner verification=new StringJoiner(",","[","]"); for(var v:store.warrantyVerifications()) verification.add("{\"id\":"+esc(v.id)+",\"serial\":"+esc(v.serial)+",\"problem\":"+esc(v.problem)+",\"status\":"+esc(v.status)+",\"result\":"+esc(v.result)+"}");
        StringJoiner compat=new StringJoiner(",","[","]"); for(var v:store.compatibilityReviews()) compat.add("{\"id\":"+esc(v.id)+",\"primary\":"+esc(v.primaryVariantId)+",\"accessory\":"+esc(v.accessoryVariantId)+",\"reason\":"+esc(v.reason)+",\"status\":"+esc(v.status)+"}");
        StringJoiner returns=new StringJoiner(",","[","]"); for(var v:store.supplierReturns()) returns.add("{\"id\":"+esc(v.id)+",\"product\":"+esc(v.variantId)+",\"serial\":"+esc(v.serial)+",\"status\":"+esc(v.status)+"}");
        StringJoiner audit=new StringJoiner(",","[","]"); for(var v:store.auditEntries().stream().limit(50).toList()) audit.add("{\"time\":"+esc(v.occurredAt.toString())+",\"actor\":"+esc(v.actor)+",\"action\":"+esc(v.action)+",\"detail\":"+esc(v.detail)+"}");
        StringJoiner events=new StringJoiner(",","[","]"); for(var v:store.interactionEvents().stream().limit(50).toList()) events.add("{\"time\":"+esc(v.occurredAt.toString())+",\"type\":"+esc(v.type)+",\"detail\":"+esc(v.detail)+"}");
        StringJoiner notifications=new StringJoiner(",","[","]"); for(var v:store.notifications(Role.ADMIN)) notifications.add("{\"title\":"+esc(v.title)+",\"message\":"+esc(v.message)+",\"read\":"+v.read+"}");
        StringJoiner roles=new StringJoiner(",","[","]"); store.demoRoleAssignments().forEach((email,role)->roles.add("{\"email\":"+esc(email)+",\"role\":"+esc(role.name())+"}"));
        var metrics=store.aiMetrics();
        StringJoiner warranties=new StringJoiner(",","[","]"); for(var w:store.warranties()) warranties.add("{\"serial\":"+esc(w.serial)+",\"customerId\":"+esc(w.customerId)+",\"product\":"+esc(w.variantId)+",\"status\":"+esc(w.status.name())+",\"expires\":"+esc(w.expiresAt.toString())+"}");
        StringJoiner rmas=new StringJoiner(",","[","]"); for(var r:store.rmas()) rmas.add("{\"id\":"+esc(r.id)+",\"customerId\":"+esc(r.customerId)+",\"serial\":"+esc(r.serial)+",\"problem\":"+esc(r.problem)+",\"status\":"+esc(r.status)+"}");
        StringJoiner requests=new StringJoiner(",","[","]"); for(var r:store.restockRequests()) requests.add("{\"id\":"+esc(r.id)+",\"product\":"+esc(r.variantId)+",\"quantity\":"+r.quantity+",\"status\":"+esc(r.status)+"}");
        String searchModel=semanticSearch!=null?semanticSearch.status():"Dịch vụ tìm kiếm AI chưa được bật";
        String speechModel=modelReady("whisper-medium","abdf7c39ab9d0397620ccaea8974cc764cd0953e","model.safetensors")?"Whisper Medium":modelReady("whisper-small","973afd24965f72e36ca33b3055d56a652f456b4d","model.safetensors")?"Whisper Small":
                Files.isRegularFile(Path.of("models","whisper-tiny","model.safetensors"))?"Whisper Tiny (dự phòng)":"Chưa cài";
        String visionModel=modelReady("siglip2-base","75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2","model.safetensors")?"SigLIP 2":modelReady("siglip-base","7fd15f0689c79d79e38b1c2e2e2370a7bf2761ed","model.safetensors")?"SigLIP":
                Files.isRegularFile(Path.of("models","resnet18-f37072fd.pth"))?"ResNet-18 (dự phòng)":"Chưa cài";
        return "{\"searchModel\":"+esc(searchModel)+",\"speechModel\":"+esc(speechModel)+",\"visionModel\":"+esc(visionModel)+",\"customerId\":"+esc(store.signedInCustomerId())+",\"name\":"+esc(store.signedInName())+",\"address\":"+esc(store.signedInAddress())+",\"cart\":"+cart+",\"promotionPercent\":"+store.promotionPercent()+",\"subtotal\":"+store.cartSubtotal()+",\"cartValidation\":"+esc(store.validateCart())+",\"cartCompatibilityWarning\":"+esc(store.cartCompatibilityWarning())+",\"orders\":"+orders+",\"warranties\":"+warranties+",\"rmas\":"+rmas+",\"requests\":"+requests+",\"categories\":"+categories+",\"reviews\":"+reviews+",\"verification\":"+verification+",\"compatibility\":"+compat+",\"supplierReturns\":"+returns+",\"audit\":"+audit+",\"events\":"+events+",\"notifications\":"+notifications+",\"roles\":"+roles+",\"consent\":"+store.interactionConsent()+",\"recommendationLimit\":"+store.recommendationLimit()+",\"queries\":"+metrics.queries()+",\"recommendationImpressions\":"+metrics.recommendationImpressions()+",\"revenue\":"+store.revenueThisMonth()+",\"available\":"+store.totalAvailable()+"}";
    }
    private static byte[] readImage(HttpExchange exchange) throws IOException {
        try(InputStream in=exchange.getRequestBody()) { byte[] data=in.readNBytes(MAX_IMAGE+1); if(data.length==0 || data.length>MAX_IMAGE)throw new IOException("Ảnh rỗng hoặc lớn hơn 30 MB."); return data; }
    }
    private static String esc(String s) {
        StringBuilder b=new StringBuilder("\""); for(char c:s.toCharArray()) { switch(c) { case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\"); case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t"); default -> { if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c); } } } return b.append('"').toString();
    }
    private static String json(ProductData p) {
        StringJoiner spec=new StringJoiner(",","{","}"); p.specs.forEach((k,v)->spec.add(esc(k)+":"+esc(v)));
        return "{\"id\":"+esc(p.id)+",\"productId\":"+esc(p.productId)+",\"productName\":"+esc(p.productName)+",\"sku\":"+esc(p.sku)+",\"name\":"+esc(p.name)+",\"category\":"+esc(p.category)+",\"brand\":"+esc(p.brand)+",\"description\":"+esc(p.description)+",\"color\":"+esc(p.color)+",\"price\":"+p.price+",\"salePrice\":"+p.salePrice+",\"effectivePrice\":"+p.effectivePrice+",\"warrantyMonths\":"+p.warrantyMonths+",\"active\":"+p.active+",\"available\":"+p.available+",\"imageAvailable\":"+p.imageAvailable+",\"specs\":"+spec+"}";
    }
    private static String details(ProductData p) {
        StringBuilder b = new StringBuilder(p.name).append("\nSKU: ").append(p.sku)
                .append("\nDanh mục: ").append(TechZoneStore.localizedCategory(p.category))
                .append("\nThương hiệu: ").append(p.brand).append("\nGiá: ").append(TechZoneStore.money(p.effectivePrice))
                .append("\nCó thể bán: ").append(p.available).append("\nBảo hành: ").append(p.warrantyMonths)
                .append(" tháng\n\n").append(p.description).append("\n\nTHÔNG SỐ NHÀ SẢN XUẤT\n");
        p.specs.forEach((key,value)->b.append("• ").append(key).append(": ").append(value).append('\n'));
        return b.toString();
    }
    private static void sendText(HttpExchange e,int status,String type,String text)throws IOException { respond(e,status,type,text.getBytes(StandardCharsets.UTF_8)); }
    private static void respond(HttpExchange e,int status,String type,byte[] bytes)throws IOException { e.getResponseHeaders().set("Content-Type",type); e.getResponseHeaders().set("Cache-Control","no-store"); e.sendResponseHeaders(status,bytes.length); try(OutputStream out=e.getResponseBody()){out.write(bytes);} }
    @Override public void close() { server.stop(0); executor.shutdownNow(); imagePrefetch.shutdownNow(); advisorChat.close(); SemanticSearch s=semanticSearch; if(s!=null) s.close(); }
}
