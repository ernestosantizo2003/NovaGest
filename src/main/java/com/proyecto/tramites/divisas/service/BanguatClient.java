package com.proyecto.tramites.divisas.service;

import com.proyecto.tramites.comun.config.BanguatProperties;
import com.proyecto.tramites.divisas.dto.CotizacionMoneda;
import com.proyecto.tramites.divisas.dto.MonedaBanguat;
import com.proyecto.tramites.divisas.dto.TipoCambioDolar;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Tarjeta 20: cliente del servicio SOAP de tipo de cambio del Banguat. El resto del sistema recibe
 * objetos Java; el XML no sale de esta clase.
 * <ul>
 * <li>Tiempo de espera y reintentos configurables (por defecto 5 s y dos reintentos).</li>
 * <li>Solo se reintenta ante fallas de red, tiempo agotado o 5xx; una respuesta invalida no se reintenta.</li>
 * <li>El XML se lee con DTD y entidades externas desactivadas (evita XXE).</li>
 * <li>Solo se envian fechas y codigos de moneda: ningun dato personal sale hacia el Banguat.</li>
 * </ul>
 * El valor de cada moneda se devuelve tal como lo publica el Banguat; para convertir hay que usar
 * {@link com.proyecto.tramites.divisas.dto.ConvencionCotizacion}.
 */
@Service
public class BanguatClient {

	static final String ESPACIO = "http://www.banguat.gob.gt/variables/ws/";

	private static final Logger LOG = LoggerFactory.getLogger(BanguatClient.class);
	private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private static final ErrorHandler SIN_RUIDO = new ErrorHandler() {
		@Override
		public void warning(SAXParseException ex) {
		}

		@Override
		public void error(SAXParseException ex) throws SAXException {
			throw ex;
		}

		@Override
		public void fatalError(SAXParseException ex) throws SAXException {
			throw ex;
		}
	};

	private final BanguatProperties propiedades;
	private final HttpClient http;

	/** Un solo constructor: Spring lo usa para crear el cliente con la configuracion app.banguat.*. */
	public BanguatClient(BanguatProperties propiedades) {
		this.propiedades = propiedades;
		this.http = HttpClient.newBuilder()
				.connectTimeout(propiedades.tiempoEspera())
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	/** Tipo de cambio de referencia del dolar para hoy (TipoCambioDia). */
	public TipoCambioDolar tipoCambioDia() {
		Document doc = llamar("TipoCambioDia", "");
		List<TipoCambioDolar> filas = filas(doc, "VarDolar",
				var -> new TipoCambioDolar(fecha(texto(var, "fecha")), numero(texto(var, "referencia"))));
		if (filas.isEmpty()) {
			throw invalida("TipoCambioDia no trajo ningun valor", null);
		}
		return filas.getFirst();
	}

	/** Dolar entre dos fechas, incluidas (TipoCambioRango). Incluye fines de semana con el ultimo valor. */
	public List<CotizacionMoneda> tipoCambioRango(LocalDate desde, LocalDate hasta) {
		validarRango(desde, hasta);
		Document doc = llamar("TipoCambioRango",
				"<fechainit>" + FECHA.format(desde) + "</fechainit><fechafin>" + FECHA.format(hasta) + "</fechafin>");
		return filas(doc, "Var", BanguatClient::cotizacion, true);
	}

	/** Otra moneda entre dos fechas, incluidas (TipoCambioRangoMoneda). Omite dias sin cotizacion. */
	public List<CotizacionMoneda> tipoCambioRangoMoneda(LocalDate desde, LocalDate hasta, int moneda) {
		validarRango(desde, hasta);
		if (moneda <= 0) {
			throw new IllegalArgumentException("El codigo de moneda debe ser positivo");
		}
		Document doc = llamar("TipoCambioRangoMoneda", "<fechainit>" + FECHA.format(desde) + "</fechainit><fechafin>"
				+ FECHA.format(hasta) + "</fechafin><moneda>" + moneda + "</moneda>");
		return filas(doc, "Var", BanguatClient::cotizacion, true);
	}

	/** Monedas que publica el Banguat, incluidas las que ya no existen (VariablesDisponibles). */
	public List<MonedaBanguat> variablesDisponibles() {
		Document doc = llamar("VariablesDisponibles", "");
		return filas(doc, "Variable",
				var -> new MonedaBanguat(entero(texto(var, "moneda")), texto(var, "descripcion").strip()));
	}

	// ---------------------------------------------------------------- HTTP y reintentos

	private Document llamar(String operacion, String parametros) {
		HttpRequest peticion = HttpRequest.newBuilder(URI.create(propiedades.url()))
				.timeout(propiedades.tiempoEspera())
				.header("Content-Type", "text/xml; charset=utf-8")
				.header("SOAPAction", "\"" + ESPACIO + operacion + "\"")
				.POST(HttpRequest.BodyPublishers.ofString(sobre(operacion, parametros), StandardCharsets.UTF_8))
				.build();

		int intentos = propiedades.reintentos() + 1;
		BanguatException ultimo = null;
		for (int intento = 1; intento <= intentos; intento++) {
			try {
				return intentar(operacion, peticion);
			}
			catch (BanguatException ex) {
				if (!ex.esReintentable()) {
					throw ex;
				}
				ultimo = ex;
				LOG.warn("Banguat {}: intento {} de {} fallo ({})", operacion, intento, intentos, ex.getMessage());
				if (intento < intentos) {
					esperar();
				}
			}
		}
		throw new BanguatException("El Banguat no respondio a " + operacion + " tras " + intentos + " intentos", true,
				ultimo);
	}

	private Document intentar(String operacion, HttpRequest peticion) {
		HttpResponse<byte[]> respuesta;
		try {
			respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofByteArray());
		}
		catch (IOException ex) { // incluye HttpTimeoutException
			throw new BanguatException("sin respuesta: " + ex.getClass().getSimpleName(), true, ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new BanguatException("consulta interrumpida", false, ex);
		}

		int estado = respuesta.statusCode();
		if (estado >= 500 && !esFalla(respuesta.body())) {
			throw new BanguatException("respondio " + estado, true, null);
		}
		Document doc = leerXml(respuesta.body());
		if (esFalla(doc)) {
			throw invalida("El Banguat rechazo la consulta " + operacion, null);
		}
		if (estado != 200) {
			throw invalida("Respuesta inesperada " + estado + " en " + operacion, null);
		}
		return doc;
	}

	private void esperar() {
		try {
			Thread.sleep(propiedades.pausaEntreReintentos());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new BanguatException("consulta interrumpida", false, ex);
		}
	}

	static String sobre(String operacion, String parametros) {
		return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
				+ "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
				+ "<soap:Body><" + operacion + " xmlns=\"" + ESPACIO + "\">" + parametros + "</" + operacion + ">"
				+ "</soap:Body></soap:Envelope>";
	}

	// ---------------------------------------------------------------- XML

	/** DOM seguro: sin DTD, sin entidades externas, sin XInclude. */
	static Document leerXml(byte[] cuerpo) {
		try {
			DocumentBuilderFactory fabrica = DocumentBuilderFactory.newInstance();
			fabrica.setNamespaceAware(true);
			fabrica.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			fabrica.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			fabrica.setFeature("http://xml.org/sax/features/external-general-entities", false);
			fabrica.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			fabrica.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			fabrica.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			fabrica.setXIncludeAware(false);
			fabrica.setExpandEntityReferences(false);
			DocumentBuilder lector = fabrica.newDocumentBuilder();
			lector.setErrorHandler(SIN_RUIDO); // el error se informa con la excepcion, no en la consola
			return lector.parse(new ByteArrayInputStream(cuerpo));
		}
		catch (ParserConfigurationException | SAXException | IOException ex) {
			throw invalida("La respuesta del Banguat no es XML valido", ex);
		}
	}

	private static boolean esFalla(byte[] cuerpo) {
		try {
			return esFalla(leerXml(cuerpo));
		}
		catch (BanguatException ex) {
			return false;
		}
	}

	private static boolean esFalla(Document doc) {
		return doc.getElementsByTagNameNS("http://schemas.xmlsoap.org/soap/envelope/", "Fault").getLength() > 0;
	}

	private static <T> List<T> filas(Document doc, String etiqueta, Function<Element, T> convertir) {
		return filas(doc, etiqueta, convertir, false);
	}

	/** En un rango, una fila ilegible no invalida las otras. XML mal formado y fallas SOAP se rechazan antes. */
	private static <T> List<T> filas(Document doc, String etiqueta, Function<Element, T> convertir,
			boolean omitirInvalidas) {
		NodeList nodos = doc.getElementsByTagNameNS(ESPACIO, etiqueta);
		List<T> resultado = new ArrayList<>(nodos.getLength());
		for (int i = 0; i < nodos.getLength(); i++) {
			try {
				resultado.add(convertir.apply((Element) nodos.item(i)));
			}
			catch (BanguatException ex) {
				if (!omitirInvalidas) {
					throw ex;
				}
			}
		}
		if (resultado.size() < nodos.getLength()) {
			LOG.warn("El Banguat envio {} filas de cotizacion ilegibles: se ignoran", nodos.getLength() - resultado.size());
		}
		return List.copyOf(resultado);
	}

	private static CotizacionMoneda cotizacion(Element var) {
		return new CotizacionMoneda(entero(texto(var, "moneda")), fecha(texto(var, "fecha")),
				numero(texto(var, "venta")), numero(texto(var, "compra")));
	}

	private static String texto(Element padre, String etiqueta) {
		NodeList hijos = padre.getChildNodes();
		for (int i = 0; i < hijos.getLength(); i++) {
			Node hijo = hijos.item(i);
			if (hijo.getNodeType() == Node.ELEMENT_NODE && etiqueta.equals(hijo.getLocalName())) {
				return hijo.getTextContent();
			}
		}
		throw invalida("Falta <" + etiqueta + "> en la respuesta del Banguat", null);
	}

	private static LocalDate fecha(String texto) {
		try {
			return LocalDate.parse(texto.strip(), FECHA);
		}
		catch (DateTimeParseException ex) {
			throw invalida("Fecha con formato inesperado en la respuesta del Banguat", ex);
		}
	}

	private static BigDecimal numero(String texto) {
		try {
			return new BigDecimal(texto.strip());
		}
		catch (NumberFormatException ex) {
			throw invalida("Valor numerico inesperado en la respuesta del Banguat", ex);
		}
	}

	private static int entero(String texto) {
		try {
			return Integer.parseInt(texto.strip());
		}
		catch (NumberFormatException ex) {
			throw invalida("Codigo de moneda inesperado en la respuesta del Banguat", ex);
		}
	}

	private static void validarRango(LocalDate desde, LocalDate hasta) {
		if (desde == null || hasta == null) {
			throw new IllegalArgumentException("Las fechas del rango son obligatorias");
		}
		if (desde.isAfter(hasta)) {
			throw new IllegalArgumentException("La fecha inicial no puede ser posterior a la final");
		}
	}

	private static BanguatException invalida(String mensaje, Throwable causa) {
		return new BanguatException(mensaje, false, causa);
	}

}
