package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.util.Matrix;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads deck.txt, fetches Scryfall images once per unique card,
 * then prints one PDF page per card copy, rotated 90°, scaled, and centered.
 */
public class Main {
    
    // -------------------- CONFIGURATION --------------------
    private static final float MM_TO_PT = 72f / 25.4f;          // mm -> points
    private static final float PAGE_W_PT = 85f * MM_TO_PT;      // 85mm width
    private static final float PAGE_H_PT = 60f * MM_TO_PT;      // 60mm height
    
    private static final String DECK_FILE = "deck.txt";
    private static final String OUTPUT_PDF = "output.pdf";
    
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build();
    
    public static void main(String[] args) throws Exception {
        File deck = new File(args.length > 0 ? args[0] : DECK_FILE);
        File out = new File(args.length > 1 ? args[1] : OUTPUT_PDF);
        
        // -------------------- STAGE 1: PARSE DECK --------------------
        List<Entry> entries = parseDeck(deck);
        System.out.println("Parsed deck: " + entries.size() + " entries.");
        
        // -------------------- STAGE 2: FETCH UNIQUE CARD IMAGES --------------------
        Map<Identifier, byte[]> imageCache = new HashMap<>();
        Set<Identifier> uniqueIds = new HashSet<>();
        for (Entry e : entries) {
            uniqueIds.add(e.toIdentifier());
        }
        
        for (Identifier id : uniqueIds) {
            try {
                System.out.println("Downloading: " + id.displayName());
                CardImage ci = fetchCardImage(id);
                if (ci.pngUrl() == null) {
                    System.err.println("No image found for: " + id.displayName());
                    continue;
                }
                byte[] imgBytes = download(ci.pngUrl());
                imageCache.put(id, imgBytes);
                Thread.sleep(120); // polite pause
            } catch (Exception ex) {
                System.err.println("Failed to fetch: " + id.displayName() + " -> " + ex.getMessage());
            }
        }
        
        // -------------------- STAGE 3: BUILD PDF --------------------
        try (PDDocument doc = new PDDocument()) {
            PDRectangle pageSize = new PDRectangle(PAGE_W_PT, PAGE_H_PT);
            
            System.out.println("Generating PDF...");
            for (Entry e : entries) {
                Identifier id = e.toIdentifier();
                byte[] imgBytes = imageCache.get(id);
                
                if (imgBytes == null) {
                    System.err.println("Skipping PDF for missing image: " + id.displayName());
                    continue;
                }
                
                for (int i = 0; i < e.count; i++) { // repeat pages per count
                    PDPage page = new PDPage(pageSize);
                    doc.addPage(page);
                    
                    PDImageXObject img = PDImageXObject.createFromByteArray(doc, imgBytes, id.displayName());
                    float imgW = img.getWidth();
                    float imgH = img.getHeight();
                    
                    // -------------------- FIXED SIZE --------------------
                    // Target size in points: 85mm × 60.89mm
                    float TARGET_W_PT = 85f * MM_TO_PT;       // 85 mm
                    float TARGET_H_PT = 60.89f * MM_TO_PT;    // 60.89 mm (slightly taller than page)
                    // it was drawW = TARGET_W_PT and drawH = TARGET_H_PT, I reversed it as a fix
                    float drawW = TARGET_H_PT;
                    float drawH = TARGET_W_PT;
                    
                    
                    // Center of page
                    float cx = PAGE_W_PT / 2f;
                    float cy = PAGE_H_PT / 2f;
                    
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.saveGraphicsState();
                        
                        // Rotate 90° clockwise around page center
                        cs.transform(Matrix.getRotateInstance(Math.PI / 2, cx, cy));
                        
                        // Draw image centered
                        cs.drawImage(img, -drawW / 2f, -drawH / 2f, drawW, drawH);
                        
                        cs.restoreGraphicsState();
                    }
                }
            }
            
            doc.save(out);
        }
        
        System.out.println("Wrote PDF: " + out.getAbsolutePath());
    }
    
    // -------------------- DECK PARSING --------------------
    private static List<Entry> parseDeck(File deckFile) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Pattern p = Pattern.compile("^\\s*(\\d+)\\s+(.+?)(?:\\s*\\(([^)]+)\\)\\s+(\\S+))?\\s*$");
        
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
            new FileInputStream(deckFile), StandardCharsets.UTF_8))) {
            
            String line;
            while ((line = br.readLine()) != null) {
                line = line.strip();
                if (line.isBlank() || line.startsWith("#")) continue;
                
                Matcher m = p.matcher(line);
                if (!m.matches()) {
                    System.err.println("Unrecognized line: " + line);
                    continue;
                }
                
                int count = Integer.parseInt(m.group(1));
                String name = m.group(2).trim();
                String set = m.group(3) != null ? m.group(3).trim() : null;
                String num = m.group(4) != null ? m.group(4).trim() : null;
                
                entries.add(new Entry(count, name, set, num));
            }
        }
        return entries;
    }
    
    // -------------------- FETCH CARD IMAGE FROM SCRYFALL --------------------
    private static CardImage fetchCardImage(Identifier id) throws Exception {
        String url = "https://api.scryfall.com/cards/named?exact=" + id.name.replace(" ", "+");
        if (id.set != null && id.collectorNumber != null) {
            url += "&set=" + id.set + "&number=" + id.collectorNumber;
        }
        
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .GET()
            .build();
        
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        
        // fallback to fuzzy search
        if (resp.statusCode() != 200) {
            url = "https://api.scryfall.com/cards/named?fuzzy=" + id.name.replace(" ", "+");
            HttpRequest req2 = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build();
            resp = HTTP.send(req2, HttpResponse.BodyHandlers.ofString());
        }
        
        JsonNode card = MAPPER.readTree(resp.body());
        JsonNode iu = card.get("image_uris");
        if (iu != null && iu.hasNonNull("png")) return new CardImage(iu.get("png").asText());
        JsonNode faces = card.get("card_faces");
        if (faces != null && faces.isArray() && faces.size() > 0) {
            JsonNode f0 = faces.get(0).get("image_uris");
            if (f0 != null && f0.hasNonNull("png")) return new CardImage(f0.get("png").asText());
        }
        if (iu != null && iu.hasNonNull("large")) return new CardImage(iu.get("large").asText());
        return new CardImage(null);
    }
    
    private static byte[] download(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build();
        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) throw new IOException("Download failed " + resp.statusCode() + " for " + url);
        return resp.body();
    }
    
    // -------------------- DATA CLASSES --------------------
    private record Entry(int count, String name, String set, String collectorNumber) {
        Identifier toIdentifier() {
            if (set != null && collectorNumber != null) return new Identifier(name, set, collectorNumber);
            return new Identifier(name, null, null);
        }
    }
    
    private record Identifier(String name, String set, String collectorNumber) {
        String displayName() {
            if (set != null && collectorNumber != null) return name + " (" + set + ") " + collectorNumber;
            return name;
        }
    }
    
    private record CardImage(String url) {
        String pngUrl() { return url; }
    }
}
