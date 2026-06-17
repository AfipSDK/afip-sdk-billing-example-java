# Afip SDK - Ejemplo de Facturacion Electronica

Ejemplo de facturacion electronica con [Afip SDK](https://afipsdk.com/) usando Java.
Genera **Facturas B** a traves de los web services de AFIP usando el paquete Maven oficial de AfipSDK y devuelve un PDF de la factura generada.

## Que hace

- Expone un endpoint `POST /bill` que recibe los datos de una factura.
- Crea un comprobante electronico (Factura B) en AFIP usando [`com.afipsdk:afip-sdk-java`](https://central.sonatype.com/artifact/com.afipsdk/afip-sdk-java).
- Genera un PDF de la factura usando templates de Afip SDK (`template: { name, params }`).
- Incluye un frontend minimo con un boton para generar una factura de prueba y descargar el PDF.

## Requisitos previos

- **Java** 8 o superior
- **Maven** 3.8 o superior
- **CUIT** del contribuyente emisor
- **Access Token** de [Afip SDK](https://app.afipsdk.com/)

**Opcional**
- **[Certificado y clave privada de AFIP](https://afipsdk.com/blog/como-obtener-certificado-para-web-services-arca/)** (archivos `.crt` y `.key`)
Si se usa el cuit `20409378472`, el certificado y key no son necesarios.

## Configuracion

Copiar el archivo de ejemplo y completar las variables:

```bash
cp .env.example .env
```

```env
AFIP_ACCESS_TOKEN=tu_access_token
AFIP_CUIT=20409378472

# Agregar estos envs si NO se usa el cuit 20409378472
AFIP_KEY_PATH=./afip-keys/key.key
AFIP_CERT_PATH=./afip-keys/cert.crt
```

| Variable | Descripcion |
|---|---|
| `AFIP_CUIT` | CUIT del contribuyente emisor |
| `AFIP_ACCESS_TOKEN` | Access token de Afip SDK |
| `AFIP_CERT_PATH` | Ruta al certificado de AFIP |
| `AFIP_KEY_PATH` | Ruta a la clave privada de AFIP |

## Uso

```bash
mvn package
java -jar target/afip-sdk-billing-example-java-1.0.0.jar
```

El servidor queda disponible en `http://localhost:4719`.

### Endpoint

**POST** `/bill`

```json
{
  "numero_de_documento": 12345678,
  "tipo_de_documento": 99,
  "importe_gravado": 100,
  "importe_exento_iva": 0,
  "importe_iva": 21,
  "punto_de_venta": 1,
  "concepto": 1,
  "condicion_iva_receptor": 5
}
```

| Campo | Tipo | Descripcion |
|---|---|---|
| `numero_de_documento` | number | Documento del receptor |
| `tipo_de_documento` | integer | Tipo de documento (80: CUIT, 86: CUIL, 96: DNI, 99: Consumidor Final) |
| `importe_gravado` | number | Importe neto gravado |
| `importe_exento_iva` | number | Importe exento de IVA |
| `importe_iva` | number | Importe de IVA (21%) |
| `punto_de_venta` | integer | Punto de venta |
| `concepto` | integer | Concepto (1: Productos, 2: Servicios, 3: Productos y Servicios) |
| `condicion_iva_receptor` | integer | Condicion frente al IVA del receptor |
| `fecha_servicio_desde` | integer | (Opcional) Fecha inicio del servicio |
| `fecha_servicio_hasta` | integer | (Opcional) Fecha fin del servicio |
| `fecha_vencimiento_pago` | integer | (Opcional) Fecha de vencimiento del pago |

La respuesta incluye la URL del PDF generado.

## Tecnologias

- [Java](https://www.java.com/) - Runtime y servidor HTTP embebido
- [Afip SDK Java](https://central.sonatype.com/artifact/com.afipsdk/afip-sdk-java) - Libreria Java de AfipSDK para web services de AFIP
- [Gson](https://github.com/google/gson) - Serializacion JSON
- Variables de entorno o archivo `.env` para configuracion local
