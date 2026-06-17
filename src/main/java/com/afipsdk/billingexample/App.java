package com.afipsdk.billingexample;

import com.afipsdk.Afip;
import com.afipsdk.exception.AfipException;
import com.afipsdk.model.AfipOptions;
import com.afipsdk.model.CreatePDFRequest;
import com.afipsdk.model.CreatePDFResponse;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public final class App {
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_PORT = 4719;

    private App() {
    }

    public static void main(String[] args) throws IOException {
        loadDotEnv();
        checkEnvs();

        int port = getPort();
        Afip afip = new Afip(createAfipOptions());
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);

        server.createContext("/bill", new BillHandler(afip));
        server.createContext("/", new StaticFileHandler(Paths.get("public")));
        server.setExecutor(null);
        server.start();

        System.out.println("Servidor iniciado en http://localhost:" + port);
    }

    private static int getPort() {
        String port = env("PORT");
        if (port == null || port.trim().isEmpty()) {
            return DEFAULT_PORT;
        }
        return Integer.parseInt(port);
    }

    private static AfipOptions createAfipOptions() throws IOException {
        AfipOptions options = new AfipOptions();
        options.setCuit(env("AFIP_CUIT"));
        options.setAccessToken(env("AFIP_ACCESS_TOKEN"));
        options.setProduction("true".equalsIgnoreCase(env("AFIP_PRODUCTION")));

        String certPath = env("AFIP_CERT_PATH");
        if (certPath != null && !certPath.trim().isEmpty()) {
            options.setCert(readFile(certPath));
        }

        String keyPath = env("AFIP_KEY_PATH");
        if (keyPath != null && !keyPath.trim().isEmpty()) {
            options.setKey(readFile(keyPath));
        }

        return options;
    }

    private static String readFile(String path) throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(path));
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void checkEnvs() {
        boolean hasCertPath = hasText(env("AFIP_CERT_PATH"));
        boolean hasKeyPath = hasText(env("AFIP_KEY_PATH"));

        if (!hasText(env("AFIP_CUIT")) ||
            !hasText(env("AFIP_ACCESS_TOKEN")) ||
            hasCertPath != hasKeyPath) {
            System.err.println("ERROR: Falta configurar variables de ambiente revise el README para mas informacion.");
            System.exit(1);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static void loadDotEnv() throws IOException {
        File file = new File(".env");
        if (!file.exists()) {
            return;
        }

        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            int separatorIndex = line.indexOf('=');
            if (separatorIndex <= 0) {
                continue;
            }

            String key = line.substring(0, separatorIndex).trim();
            String value = line.substring(separatorIndex + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }

            setEnvIfMissing(key, value);
        }
    }

    private static void setEnvIfMissing(String key, String value) {
        if (System.getenv(key) != null) {
            return;
        }

        System.setProperty(key, value);
    }

    private static String env(String key) {
        String value = System.getenv(key);
        return value != null ? value : System.getProperty(key);
    }

    private static final class BillHandler implements HttpHandler {
        private final Afip afip;

        private BillHandler(Afip afip) {
            this.afip = afip;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, new ErrorResponse("Metodo no permitido."));
                return;
            }

            try {
                BillRequest request = GSON.fromJson(readBody(exchange), BillRequest.class);
                if (request == null) {
                    sendJson(exchange, 400, new ErrorResponse("Request invalido."));
                    return;
                }

                sendJson(exchange, 200, createBill(request));
            } catch (JsonSyntaxException e) {
                sendJson(exchange, 400, new ErrorResponse("JSON invalido."));
            } catch (AfipException e) {
                sendJson(exchange, 400, new ErrorResponse(e.getMessage()));
            } catch (Exception e) {
                sendJson(exchange, 500, new ErrorResponse(e.getMessage()));
            }
        }

        private PdfResponse createBill(BillRequest request) {
            int tipoDeFactura = 6; // Factura B
            int lastVoucher = afip.electronicBilling().getLastVoucher(request.puntoDeVenta, tipoDeFactura);
            Map<String, Object> voucherInfo = lastVoucher > 0
                ? afip.electronicBilling().getVoucherInfo(lastVoucher, request.puntoDeVenta, tipoDeFactura)
                : null;

            int numeroDeFactura = lastVoucher + 1;
            BigDecimal importeTotal = request.importeGravado
                .add(request.importeIva)
                .add(request.importeExentoIva);
            int fecha = Math.max(getMapInt(voucherInfo, "CbteFch", 0), getTodayAsNumber());

            Map<String, Object> alicuota = new HashMap<String, Object>();
            alicuota.put("Id", 5);
            alicuota.put("BaseImp", request.importeGravado);
            alicuota.put("Importe", request.importeIva);

            Map<String, Object> voucherData = new HashMap<String, Object>();
            voucherData.put("CantReg", 1);
            voucherData.put("PtoVta", request.puntoDeVenta);
            voucherData.put("CbteTipo", tipoDeFactura);
            voucherData.put("Concepto", request.concepto);
            voucherData.put("DocTipo", request.tipoDeDocumento);
            voucherData.put("DocNro", request.numeroDeDocumento);
            voucherData.put("CbteDesde", numeroDeFactura);
            voucherData.put("CbteHasta", numeroDeFactura);
            voucherData.put("CbteFch", fecha);
            voucherData.put("FchServDesde", request.fechaServicioDesde);
            voucherData.put("FchServHasta", request.fechaServicioHasta);
            voucherData.put("FchVtoPago", request.fechaVencimientoPago);
            voucherData.put("ImpTotal", importeTotal);
            voucherData.put("ImpTotConc", BigDecimal.ZERO);
            voucherData.put("ImpNeto", request.importeGravado);
            voucherData.put("ImpOpEx", request.importeExentoIva);
            voucherData.put("ImpIVA", request.importeIva);
            voucherData.put("ImpTrib", BigDecimal.ZERO);
            voucherData.put("MonId", "PES");
            voucherData.put("MonCotiz", 1);
            voucherData.put("CondicionIVAReceptorId", request.condicionIvaReceptor);
            voucherData.put("Iva", Arrays.asList(alicuota));

            Map<String, Object> billResponse = afip.electronicBilling().createVoucher(voucherData);
            CreatePDFResponse pdfResponse = afip.electronicBilling().createPDF(createPdfRequest(
                request,
                numeroDeFactura,
                fecha,
                importeTotal,
                stringValue(billResponse.get("CAE")),
                stringValue(billResponse.get("CAEFchVto"))
            ));

            return new PdfResponse(pdfResponse.getFile(), pdfResponse.getFileName());
        }

        private CreatePDFRequest createPdfRequest(
            BillRequest request,
            int numeroDeFactura,
            int fecha,
            BigDecimal importeTotal,
            String cae,
            String caeVencimiento) {
            String cuit = env("AFIP_CUIT");
            Object issuerCuit = parseLongOrString(cuit);
            String parsedDate = formatDateNumber(fecha);

            Map<String, Object> templateParams = new HashMap<String, Object>();
            templateParams.put("voucher_number", numeroDeFactura);
            templateParams.put("sales_point", request.puntoDeVenta);
            templateParams.put("issue_date", parsedDate);
            templateParams.put("cae_due_date", formatIsoDateForDisplay(caeVencimiento));
            templateParams.put("issuer_cuit", issuerCuit);
            templateParams.put("cae", cae);
            templateParams.put("issuer_business_name", "Empresa imaginaria S.A.");
            templateParams.put("issuer_address", "Calle falsa 123");
            templateParams.put("issuer_iva_condition", "Responsable inscripto");
            templateParams.put("issuer_gross_income", cuit);
            templateParams.put("issuer_activity_start_date", parsedDate);
            templateParams.put("receiver_name", "Consumidor Final");
            templateParams.put("receiver_address", "-");
            templateParams.put("receiver_document_type", 99);
            templateParams.put("receiver_document_number", request.numeroDeDocumento);
            templateParams.put("receiver_iva_condition", String.valueOf(request.condicionIvaReceptor));
            templateParams.put("sale_condition", "Contado");
            templateParams.put("currency_id", "ARS");
            templateParams.put("currency_rate", 1);
            templateParams.put("concept", 1);
            templateParams.put("items", Arrays.asList(createItem(importeTotal)));
            templateParams.put("vat_amount", request.importeIva);
            templateParams.put("tributes_amount", 0);
            templateParams.put("total_amount", importeTotal);

            if (request.fechaServicioDesde != null) {
                templateParams.put("billing_from", formatDateNumber(request.fechaServicioDesde));
            }
            if (request.fechaServicioHasta != null) {
                templateParams.put("billing_to", formatDateNumber(request.fechaServicioHasta));
            }
            if (request.fechaVencimientoPago != null) {
                templateParams.put("payment_due_date", formatDateNumber(request.fechaVencimientoPago));
            }

            Map<String, Object> template = new HashMap<String, Object>();
            template.put("name", "invoice-b");
            template.put("params", templateParams);

            CreatePDFRequest pdfRequest = new CreatePDFRequest();
            pdfRequest.setFileName(String.format(Locale.ROOT, "factura-b-%08d.pdf", numeroDeFactura));
            pdfRequest.setTemplate(template);
            return pdfRequest;
        }

        private Map<String, Object> createItem(BigDecimal importeTotal) {
            Map<String, Object> item = new HashMap<String, Object>();
            item.put("code", "001");
            item.put("description", "Servicio");
            item.put("quantity", 1);
            item.put("unit_price", importeTotal);
            item.put("subtotal", importeTotal);
            return item;
        }
    }

    private static final class StaticFileHandler implements HttpHandler {
        private final Path root;

        private StaticFileHandler(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) &&
                !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, new ErrorResponse("Metodo no permitido."));
                return;
            }

            String requestPath = URLDecoder.decode(exchange.getRequestURI().getPath(), "UTF-8");
            if ("/".equals(requestPath)) {
                requestPath = "/index.html";
            }

            Path file = root.resolve(requestPath.substring(1)).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                sendText(exchange, 404, "Not found", "text/plain; charset=utf-8");
                return;
            }

            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType(file));
            byte[] bytes = Files.readAllBytes(file);
            exchange.sendResponseHeaders(200, "HEAD".equalsIgnoreCase(exchange.getRequestMethod()) ? -1 : bytes.length);
            if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                OutputStream responseBody = exchange.getResponseBody();
                responseBody.write(bytes);
                responseBody.close();
            }
        }

        private String contentType(Path file) {
            String name = file.getFileName().toString();
            if (name.endsWith(".html")) {
                return "text/html; charset=utf-8";
            }
            if (name.endsWith(".css")) {
                return "text/css; charset=utf-8";
            }
            if (name.endsWith(".js")) {
                return "application/javascript; charset=utf-8";
            }
            return "application/octet-stream";
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream input = exchange.getRequestBody();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = input.read(buffer)) != -1) {
            output.write(buffer, 0, length);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        sendText(exchange, statusCode, GSON.toJson(body), "application/json; charset=utf-8");
    }

    private static void sendText(HttpExchange exchange, int statusCode, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        OutputStream responseBody = exchange.getResponseBody();
        responseBody.write(bytes);
        responseBody.close();
    }

    private static int getTodayAsNumber() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return Integer.parseInt(format.format(new Date()));
    }

    private static String formatDateNumber(int dateNumber) {
        String text = String.valueOf(dateNumber);
        return text.substring(6, 8) + "/" + text.substring(4, 6) + "/" + text.substring(0, 4);
    }

    private static String formatIsoDateForDisplay(String isoDate) {
        if (isoDate == null) {
            return "";
        }
        String[] parts = isoDate.split("-");
        return parts.length == 3 ? parts[2] + "/" + parts[1] + "/" + parts[0] : isoDate;
    }

    private static int getMapInt(Map<String, Object> data, String key, int defaultValue) {
        if (data == null || !data.containsKey(key) || data.get(key) == null) {
            return defaultValue;
        }

        Object value = data.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private static Object parseLongOrString(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static final class BillRequest {
        @SerializedName("numero_de_documento")
        private long numeroDeDocumento;

        @SerializedName("tipo_de_documento")
        private int tipoDeDocumento;

        @SerializedName("importe_gravado")
        private BigDecimal importeGravado = BigDecimal.ZERO;

        @SerializedName("importe_exento_iva")
        private BigDecimal importeExentoIva = BigDecimal.ZERO;

        @SerializedName("importe_iva")
        private BigDecimal importeIva = BigDecimal.ZERO;

        @SerializedName("punto_de_venta")
        private int puntoDeVenta;

        @SerializedName("concepto")
        private int concepto;

        @SerializedName("condicion_iva_receptor")
        private int condicionIvaReceptor;

        @SerializedName("fecha_servicio_desde")
        private Integer fechaServicioDesde;

        @SerializedName("fecha_servicio_hasta")
        private Integer fechaServicioHasta;

        @SerializedName("fecha_vencimiento_pago")
        private Integer fechaVencimientoPago;
    }

    private static final class PdfResponse {
        private final String file;

        @SerializedName("file_name")
        private final String fileName;

        private PdfResponse(String file, String fileName) {
            this.file = file;
            this.fileName = fileName;
        }
    }

    private static final class ErrorResponse {
        private final String message;

        private ErrorResponse(String message) {
            this.message = message;
        }
    }
}
