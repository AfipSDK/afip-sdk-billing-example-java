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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public final class App {
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_PORT = 4719;

    private App() {
    }

    public static void main(String[] args) throws IOException {
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
        String port = System.getenv("PORT");
        if (port == null || port.trim().isEmpty()) {
            return DEFAULT_PORT;
        }
        return Integer.parseInt(port);
    }

    private static AfipOptions createAfipOptions() throws IOException {
        AfipOptions options = new AfipOptions();
        options.setAccessToken(System.getenv("AFIP_TOKEN"));
        options.setCuit(System.getenv("AFIP_CUIT"));
        options.setProduction(false);

        String certPath = System.getenv("AFIP_CERT_PATH");
        if (certPath != null && !certPath.trim().isEmpty()) {
            options.setCert(readFile(certPath));
        }

        String keyPath = System.getenv("AFIP_KEY_PATH");
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
        boolean hasCertPath = hasText(System.getenv("AFIP_CERT_PATH"));
        boolean hasKeyPath = hasText(System.getenv("AFIP_KEY_PATH"));

        if (!hasText(System.getenv("AFIP_CUIT")) ||
            !hasText(System.getenv("AFIP_TOKEN")) ||
            hasCertPath != hasKeyPath) {
            System.err.println("ERROR: Falta configurar variables de ambiente revise el README para mas informacion.");
            System.exit(1);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static final class BillHandler implements HttpHandler {
        private final Afip afip;

        private BillHandler(Afip afip) {
            this.afip = afip;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, error("Metodo no permitido."));
                return;
            }

            try {
                BillRequest request = GSON.fromJson(readBody(exchange), BillRequest.class);
                if (request == null) {
                    sendJson(exchange, 400, error("Request invalido."));
                    return;
                }

                sendJson(exchange, 200, createBill(request));
            } catch (Exception e) {
                int statusCode = e instanceof JsonSyntaxException || e instanceof AfipException ? 400 : 500;
                String message = e instanceof JsonSyntaxException ? "JSON invalido." : e.getMessage();
                sendJson(exchange, statusCode, error(message));
            }
        }

        private CreatePDFResponse createBill(BillRequest request) {
            int tipoDeFactura = 6; // Factura B
            int lastVoucher = afip.electronicBilling().getLastVoucher(request.puntoDeVenta, tipoDeFactura);
            int numeroDeFactura = lastVoucher + 1;
            int fecha = getTodayAsNumber();
            double importeTotal = request.importeGravado + request.importeIva + request.importeExentoIva;

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
            voucherData.put("ImpTotal", importeTotal);
            voucherData.put("ImpTotConc", 0);
            voucherData.put("ImpNeto", request.importeGravado);
            voucherData.put("ImpOpEx", request.importeExentoIva);
            voucherData.put("ImpIVA", request.importeIva);
            voucherData.put("ImpTrib", 0);
            voucherData.put("MonId", "PES");
            voucherData.put("MonCotiz", 1);
            voucherData.put("CondicionIVAReceptorId", request.condicionIvaReceptor);
            voucherData.put("Iva", Arrays.asList(alicuota));

            if (request.concepto == 2 || request.concepto == 3) {
                voucherData.put("FchServDesde", request.fechaServicioDesde);
                voucherData.put("FchServHasta", request.fechaServicioHasta);
                voucherData.put("FchVtoPago", request.fechaVencimientoPago);
            }

            Map<String, Object> billResponse = afip.electronicBilling().createVoucher(voucherData);
            Object cae = billResponse.get("CAE");
            Object caeVencimiento = billResponse.get("CAEFchVto");

            return afip.electronicBilling().createPDF(createPdfRequest(
                request,
                numeroDeFactura,
                fecha,
                importeTotal,
                cae != null ? cae.toString() : "",
                caeVencimiento != null ? caeVencimiento.toString() : ""
            ));
        }

        private CreatePDFRequest createPdfRequest(
            BillRequest request,
            int numeroDeFactura,
            int fecha,
            double importeTotal,
            String cae,
            String caeVencimiento) {
            String cuit = System.getenv("AFIP_CUIT");
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

        private Map<String, Object> createItem(double importeTotal) {
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
                sendJson(exchange, 405, error("Metodo no permitido."));
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

    private static Object parseLongOrString(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> error = new HashMap<String, Object>();
        error.put("message", message);
        return error;
    }

    private static final class BillRequest {
        @SerializedName("numero_de_documento")
        private long numeroDeDocumento;

        @SerializedName("tipo_de_documento")
        private int tipoDeDocumento;

        @SerializedName("importe_gravado")
        private double importeGravado;

        @SerializedName("importe_exento_iva")
        private double importeExentoIva;

        @SerializedName("importe_iva")
        private double importeIva;

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
}
