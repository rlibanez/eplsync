package com.rlibanez.eplsync.performance;

import com.rlibanez.eplsync.security.AccountStore;
import java.lang.management.ManagementFactory;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in HTTP benchmark: independent accounts/cookies, real Tomcat, SQLite/WAL and human think time. */
@EnabledIfSystemProperty(named="eplsync.test.user-load", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "eplsync.security.require-https=false", "eplsync.security.initial-admin-key=",
    "eplsync.torrent.enabled=false", "eplsync.torrent.bulk.worker-enabled=false",
    "eplsync.torrent.cleanup.enabled=false", "eplsync.torrent.bulk.retention.enabled=false",
    "eplsync.updates.scheduler-enabled=false", "server.tomcat.accesslog.enabled=false",
    "server.address=127.0.0.1", "logging.file.name=target/user-load/eplsync.log"})
class ConcurrentUserLoadTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:"+directory.resolve("users.db"));
        // The general test defaults use one connection for :memory:, not the production pool.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountStore accounts;
    final JsonMapper json=JsonMapper.builder().build();
    static final String PASSWORD="Permanent test password 2026";
    static final String[] TITLES={"Árbol de la memoria", "El viaje", "¿Quién recuerda?", "Ñandú", "La biblioteca", "Épocas", "Historia del mundo", "La noche", "Ciencia y vida", "Órbita", "Una aventura", "El mar"};
    static final String[] GENRES={"Novela", "Historia", "Ciencia", "Ensayo", "Fantasía", "Misterio"};
    record Sample(String operation, double milliseconds, int status, int bytes, String error) {}
    record User(HttpClient client, int index) {}

    @BeforeEach void prepareFixture() {
        long count=jdbc.queryForObject("SELECT count(*) FROM catalog_books",Long.class);
        if(count==0) seed();
        else assertThat(count).isEqualTo(73000);
    }
    void seed() {
        String synopsis="Sinopsis sintética de prueba con contenido y descripción del libro. ".repeat(16);
        for(int start=1;start<=73000;start+=1000) {
            var batch=new ArrayList<Object[]>();
            for(int id=start;id<Math.min(start+1000,73001);id++) {
                String author="Autor "+String.format(Locale.ROOT,"%04d",id%6000);
                if(id%5==0) author+=" & Autor "+String.format(Locale.ROOT,"%04d",(id+21)%6000);
                batch.add(new Object[]{id,1.0+(id%10)/10.0,author,TITLES[id%TITLES.length]+" "+String.format(Locale.ROOT,"%06d",id),
                    synopsis,GENRES[id%GENRES.length]+", Literatura","Colección "+(id%500),"ESPANOL",100+id%900,
                    id%2==0?"PUBLISHED":"UPDATED",java.sql.Date.valueOf("2026-09-"+String.format(Locale.ROOT,"%02d",id%28+1)),
                    java.sql.Timestamp.from(java.time.Instant.parse("2026-10-01T00:00:00Z"))});
            }
            jdbc.batchUpdate("INSERT INTO catalog_books(epl_id,revision,author,title,synopsis,genres,collection,language,pages,publication_status,publication_date,insert_date) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",batch);
        }
        // Two historical revisions per book, including for readers allowed to see history.
        for(int revision=1;revision<=2;revision++)
            jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,origin,status,created_at,submitted_at) SELECT ?||epl_id,epl_id,?,printf('%040X',?+epl_id),'qbittorrent','load-test','EPLSYNC','DOWNLOADED',1,1 FROM catalog_books", "r"+revision+"-",revision*0.4,revision*1000000);
        accounts.initialize("load-admin","admin@example.test",PASSWORD,PASSWORD);
        accounts.policy(new AccountStore.Policy(true,false,30,12));
        for(int index=0;index<10;index++) {
            String username="load-user-"+index;
            accounts.register(username,username+"@example.test",PASSWORD);
            if(index%2==0) {
                String id=jdbc.queryForObject("SELECT id FROM users WHERE username=?",String.class,username);
                accounts.update(id,"USER","ACTIVE",Map.of("BOOK_HISTORY_READ","ALLOW"),null);
            }
        }
        assertThat(jdbc.queryForObject("PRAGMA journal_mode",String.class)).isEqualToIgnoringCase("wal");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_books",Long.class)).isEqualTo(73000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_downloads",Long.class)).isEqualTo(146000);
    }
    URI uri(String path) {return URI.create("http://127.0.0.1:"+port+path);}
    static String encode(String value) {return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    User login(int index) throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(5)).build();
        var csrf=client.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(csrf.statusCode()).isEqualTo(200);
        var token=json.readTree(csrf.body());
        var response=client.send(HttpRequest.newBuilder(uri("/api/auth/login"))
            .header("Content-Type","application/json")
            .header(token.get("headerName").asText(),token.get("token").asText())
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username","load-user-"+index,"password",PASSWORD))))
            .build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).describedAs("login for account %d",index).isEqualTo(200);
        return new User(client,index);
    }
    void get(User user,String operation,String path,Queue<Sample> samples) {
        long begin=System.nanoTime(); int status=0,bytes=0;String error=null;
        try {
            var response=user.client().send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.ofString());
            status=response.statusCode();bytes=response.body().getBytes(StandardCharsets.UTF_8).length;
            if(status==200) {
                JsonNode body=json.readTree(response.body());
                if(path.startsWith("/api/catalog/books?") || path.startsWith("/api/catalog/directory/")) {
                    if(!body.has("items") || !body.has("meta") || body.get("items").size()>50)
                        error="Invalid paginated response";
                    if(path.startsWith("/api/catalog/books?") && body.get("items").isEmpty())
                        error="Unexpected empty catalog page";
                } else if(path.startsWith("/api/catalog/books/") && !body.has("eplId")) error="Invalid book response";
                else if(path.startsWith("/api/catalog/suggestions/") && !body.has("items")) error="Invalid suggestions response";
            } else error=response.body().substring(0,Math.min(300,response.body().length()));
        } catch(Exception ex) {error=ex.getClass().getSimpleName()+": "+ex.getMessage();}
        samples.add(new Sample(operation,(System.nanoTime()-begin)/1e6,status,bytes,error));
    }
    void action(User user,int step,SplittableRandom random,Queue<Sample> samples) {
        switch(step%8) {
            case 0 -> get(user,"catalog-page","/api/catalog/books?page="+random.nextInt(0,20)+"&size=50&sort=title,asc",samples);
            case 1 -> {
                get(user,"suggest-title","/api/catalog/suggestions/titles?q="+encode("La"),samples);
                get(user,"catalog-title","/api/catalog/books?page=0&size=50&sort=title,asc&title="+encode("La"),samples);
            }
            case 2 -> get(user,"catalog-deep-page","/api/catalog/books?page="+random.nextInt(900,1100)+"&size=50&sort=title,asc",samples);
            case 3 -> {
                get(user,"suggest-author","/api/catalog/suggestions/authors?q="+encode("Autor 00"),samples);
                get(user,"catalog-author","/api/catalog/books?page=0&size=50&sort=title,asc&author="+encode("Autor 00"),samples);
            }
            case 4 -> get(user,"directory-authors","/api/catalog/directory/authors?page="+random.nextInt(0,20)+"&size=50",samples);
            case 5 -> {
                get(user,"suggest-directory","/api/catalog/suggestions/authors?q="+encode("Autor 01"),samples);
                get(user,"directory-search","/api/catalog/directory/authors?page=0&size=50&q="+encode("Autor 01"),samples);
            }
            case 6 -> get(user,"directory-genres","/api/catalog/directory/genres?page=0&size=50",samples);
            default -> get(user,"book-detail","/api/catalog/books/"+random.nextInt(1,73001),samples);
        }
    }
    void catalogSearch(User user,int step,SplittableRandom random,Queue<Sample> samples) {
        String ordering=step%2==0 ? "title,asc" : "author,asc";
        String base="/api/catalog/books?page="+random.nextInt(0,3)+"&size=50&sort="+ordering;
        switch(step%3) {
            case 0 -> {
                String[] terms={"La","El","Historia","memoria","Ciencia","aventura","viaje"};
                get(user,"search-title",base+"&title="+encode(terms[random.nextInt(terms.length)]),samples);
            }
            case 1 -> get(user,"search-author",base+"&author="+encode("Autor 0"+random.nextInt(0,6)),samples);
            default -> get(user,"search-combined",base+"&title="+encode("La")+"&author="+encode("Autor 0"+random.nextInt(0,6))+"&pagesFrom=200&pagesTo=800",samples);
        }
    }
    Map<String,Object> simultaneousSearches(List<User> users,int rounds) throws Exception {
        var samples=new ConcurrentLinkedQueue<Sample>();var launchSpreads=new ArrayList<Double>();
        long begin=System.nanoTime();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            for(int round=0;round<rounds;round++) {
                int iteration=round;var ready=new CountDownLatch(users.size());var start=new CountDownLatch(1);
                var launches=new ConcurrentLinkedQueue<Long>();var tasks=new ArrayList<Future<?>>();
                for(var user:users) tasks.add(executor.submit(() -> {
                    ready.countDown();start.await();launches.add(System.nanoTime());
                    catalogSearch(user,iteration+user.index(),new SplittableRandom(20261010L+iteration*10L+user.index()),samples);
                    return null;
                }));
                if(!ready.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Users did not reach the launch barrier");
                start.countDown();for(var task:tasks) task.get(30,TimeUnit.SECONDS);
                launchSpreads.add((Collections.max(launches)-Collections.min(launches))/1e6);
                if(round+1<rounds) Thread.sleep(250);
            }
        }
        var report=new LinkedHashMap<String,Object>();report.put("users",users.size());report.put("rounds",rounds);
        report.put("elapsedSeconds",(System.nanoTime()-begin)/1e9);report.put("maxLaunchSpreadMs",Collections.max(launchSpreads));
        report.put("overall",metrics(samples));
        report.put("errorExamples",samples.stream().filter(sample -> sample.error()!=null).limit(20).toList());
        System.out.println("CATALOG_BURST "+json.writeValueAsString(report));
        return report;
    }
    @Test @Timeout(value=4,unit=TimeUnit.MINUTES)
    void simulateTenCatalogSearchUsersAndSimultaneousBursts() throws Exception {
        var users=new ArrayList<User>();
        try {
            for(int index=0;index<10;index++) users.add(login(index));
            var warm=new ConcurrentLinkedQueue<Sample>();
            for(int step=0;step<6;step++) catalogSearch(users.getFirst(),step,new SplittableRandom(step),warm);
            var searches=run(users,60,true);
            var bursts=simultaneousSearches(users,20);
            var report=new LinkedHashMap<String,Object>();
            report.put("createdAt",java.time.Instant.now().toString());report.put("catalogBooks",73000);report.put("historyRecords",146000);
            report.put("database","SQLite on temporary local disk, WAL, FULL, pool=2");
            report.put("maxHeapMiB",Runtime.getRuntime().maxMemory()/1048576);
            report.put("permissions","CATALOG_READ for all; BOOK_HISTORY_READ for half");
            report.put("continuous",searches);report.put("bursts",bursts);
            Path output=Path.of("target/user-load/catalog-search-results.json");Files.createDirectories(output.getParent());
            Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            assertThat(warm).allSatisfy(sample -> {assertThat(sample.status()).isEqualTo(200);assertThat(sample.error()).isNull();});
            for(var scenario:List.of(searches,bursts))
                assertThat(((Map<?,?>)scenario.get("overall")).get("errors")).isEqualTo(0L);
            assertThat(((Map<?,?>)bursts.get("overall")).get("requests")).isEqualTo(200);
        } finally {for(var user:users) user.client().close();}
    }
    static double percentile(List<Double> values,double fraction) {
        return values.get(Math.min(values.size()-1,(int)Math.ceil(values.size()*fraction)-1));
    }
    Map<String,Object> metrics(Collection<Sample> samples) {
        var times=samples.stream().map(Sample::milliseconds).sorted().toList();
        var codes=new TreeMap<Integer,Long>();for(var sample:samples) codes.merge(sample.status(),1L,Long::sum);
        var result=new LinkedHashMap<String,Object>();
        result.put("requests",samples.size()); result.put("statusCounts",codes);
        result.put("errors",samples.stream().filter(s -> s.error()!=null).count());
        result.put("meanMs",times.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        result.put("p50Ms",percentile(times,0.5));result.put("p95Ms",percentile(times,0.95));
        result.put("p99Ms",percentile(times,0.99));result.put("maxMs",times.getLast());
        return result;
    }
    Map<String,Object> run(List<User> users,int seconds) throws Exception {
        return run(users,seconds,false);
    }
    Map<String,Object> run(List<User> users,int seconds,boolean catalogOnly) throws Exception {
        var samples=new ConcurrentLinkedQueue<Sample>();var peak=new AtomicLong();
        long begin=System.nanoTime();long deadline=begin+TimeUnit.SECONDS.toNanos(seconds);
        var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();long cpuBefore=os.getProcessCpuTime();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor();var sampler=Executors.newSingleThreadScheduledExecutor()) {
            var sampling=sampler.scheduleAtFixedRate(() -> peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),Math::max),0,100,TimeUnit.MILLISECONDS);
            try {
                var start=new CountDownLatch(1);var tasks=new ArrayList<Future<?>>();
                for(var user:users) tasks.add(executor.submit(() -> {
                    start.await();var random=new SplittableRandom(20261010L+user.index());int step=user.index();
                    // Spread initial navigation by at most 1 second, then wait 1–2 seconds between user actions.
                    Thread.sleep(random.nextInt(0,1001));
                    while(System.nanoTime()<deadline) {
                        if(catalogOnly) catalogSearch(user,step++,random,samples);
                        else action(user,step++,random,samples);
                        long remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
                        if(remaining>0) Thread.sleep(Math.min(remaining,random.nextInt(1000,2001)));
                    }
                    return null;
                }));
                start.countDown();for(var task:tasks) task.get(seconds+30,TimeUnit.SECONDS);
            } finally {sampling.cancel(false);}
        }
        double elapsed=(System.nanoTime()-begin)/1e9;
        var result=new LinkedHashMap<String,Object>();result.put("users",users.size());result.put("elapsedSeconds",elapsed);
        result.put("throughputRequestsPerSecond",samples.size()/elapsed);result.put("peakHeapMiB",peak.get()/1048576.0);
        result.put("averageCpuCores",(os.getProcessCpuTime()-cpuBefore)/1e9/elapsed);result.put("overall",metrics(samples));
        var operations=new TreeMap<String,Object>();
        for(String operation:samples.stream().map(Sample::operation).distinct().sorted().toList())
            operations.put(operation,metrics(samples.stream().filter(s -> s.operation().equals(operation)).toList()));
        result.put("operations",operations);result.put("errorExamples",samples.stream().filter(s -> s.error()!=null).limit(20).toList());
        System.out.println("USER_LOAD "+json.writeValueAsString(result));
        return result;
    }
    @Test @Timeout(value=6,unit=TimeUnit.MINUTES)
    void simulateCatalogAndDirectoryUsers() throws Exception {
        var users=new ArrayList<User>();for(int index=0;index<10;index++) users.add(login(index));
        // Validate all routes and warm the suggestion vocabularies; retain cold timings separately.
        var cold=new ConcurrentLinkedQueue<Sample>();for(int step=0;step<8;step++) action(users.getFirst(),step,new SplittableRandom(step),cold);
        var scenarios=new ArrayList<Map<String,Object>>();
        scenarios.add(run(users.subList(0,1),15));
        scenarios.add(run(users.subList(0,5),60));
        scenarios.add(run(users,60));
        var report=new LinkedHashMap<String,Object>();
        report.put("createdAt",java.time.Instant.now().toString());report.put("catalogBooks",73000);report.put("historyRecords",146000);
        report.put("database","SQLite on temporary local disk, WAL, FULL, pool=2");
        report.put("maxHeapMiB",Runtime.getRuntime().maxMemory()/1048576);report.put("availableProcessors",Runtime.getRuntime().availableProcessors());
        report.put("thinkTime","1–2 seconds between actions, no automatic retries");
        report.put("permissions","CATALOG_READ for all; BOOK_HISTORY_READ for half");
        report.put("coldRequests",cold);report.put("scenarios",scenarios);
        Path output=Path.of("target/user-load/results.json");Files.createDirectories(output.getParent());
        Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        assertThat(cold).allSatisfy(sample -> {assertThat(sample.status()).isEqualTo(200);assertThat(sample.error()).isNull();});
        assertThat(scenarios).allSatisfy(scenario -> assertThat(((Map<?,?>)scenario.get("overall")).get("errors")).isEqualTo(0L));
        for(var user:users) user.client().close();
    }
}
