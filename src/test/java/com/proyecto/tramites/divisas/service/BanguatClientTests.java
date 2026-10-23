package com.proyecto.tramites.divisas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proyecto.tramites.comun.config.BanguatProperties;
import com.proyecto.tramites.divisas.dto.CotizacionMoneda;
import com.proyecto.tramites.divisas.dto.MonedaBanguat;
import com.proyecto.tramites.divisas.dto.TipoCambioDolar;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tarjeta 20: el cliente del Banguat se prueba con las respuestas reales grabadas en
 * docs/banguat/muestras/, servidas por un servidor HTTP local. Nunca se llama al servicio en vivo.
 */
class BanguatClientTests {

	private static final Path MUESTRAS = Path.of("docs", "banguat", "muestras");

	/** Que responde el servidor local en cada intento: estado, cuerpo y demora. */
	private record Respuesta(int estado, byte[] cuerpo, Duration demora) {

		static Respuesta muestra(String archivo) throws IOException {
			return new Respuesta(200, Files.readAllBytes(MUESTRAS.resolve(archivo)), Duration.ZERO);
		}

		static Respuesta texto(int estado, String cuerpo) {
			return new Respuesta(estado, cuerpo.getBytes(StandardCharsets.UTF_8), Duration.ZERO);
		}

	}

	private record Peticion(String soapAction, String contentType, String cuerpo) {
	}

	private HttpServer servidor;
	private final List<Respuesta> guion = new CopyOnWriteArrayList<>();
	private final List<Peticion> recibidas = new CopyOnWriteArrayList<>();
	private final AtomicInteger turno = new AtomicInteger();

	@BeforeEach
	void levantarServidor() throws IOException {
		servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		servidor.createContext("/variables/ws/TipoCambio.asmx", this::responder);
		servidor.setExecutor(Executors.newCachedThreadPool()); // una respuesta lenta no bloquea el reintento
		servidor.start();
	}

	@AfterEach
	void apagarServidor() {
		servidor.stop(0);
	}

	private void responder(HttpExchange intercambio) throws IOException {
		recibidas.add(new Peticion(intercambio.getRequestHeaders().getFirst("SOAPAction"),
				intercambio.getRequestHeaders().getFirst("Content-Type"),
				new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
		Respuesta r = guion.get(Math.min(turno.getAndIncrement(), guion.size() - 1));
		try {
			Thread.sleep(r.demora());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		intercambio.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
		intercambio.sendResponseHeaders(r.estado(), r.cuerpo().length);
		try (OutputStream salida = intercambio.getResponseBody()) {
			salida.write(r.cuerpo());
		}
	}

	private BanguatClient cliente(Duration espera, int reintentos) {
		String url = "http://" + servidor.getAddress().getHostString() + ":" + servidor.getAddress().getPort()
				+ "/variables/ws/TipoCambio.asmx";
		return new BanguatClient(new BanguatProperties(url, espera, reintentos, Duration.ZERO));
	}

	private BanguatClient cliente() {
		return cliente(Duration.ofSeconds(5), 2);
	}

	private static LocalDate dia(int d) {
		return LocalDate.of(2026, 10, d);
	}

	// ---------------------------------------------------------------- respuestas reales

	@Test
	void leeElTipoDeCambioDelDiaDeLaMuestraReal() throws Exception {
		guion.add(Respuesta.muestra("TipoCambioDia.xml"));

		TipoCambioDolar hoy = cliente().tipoCambioDia();

		assertThat(hoy.fecha()).isEqualTo(dia(15));
		assertThat(hoy.referencia()).isEqualByComparingTo("7.6414");
	}

	@Test
	void enviaLaOperacionSoapCorrecta() throws Exception {
		guion.add(Respuesta.muestra("TipoCambioRangoMoneda.xml"));

		cliente().tipoCambioRangoMoneda(dia(1), dia(5), 24);

		Peticion p = recibidas.getFirst();
		assertThat(p.soapAction()).isEqualTo("\"http://www.banguat.gob.gt/variables/ws/TipoCambioRangoMoneda\"");
		assertThat(p.contentType()).startsWith("text/xml");
		assertThat(p.cuerpo()).contains("<TipoCambioRangoMoneda xmlns=\"http://www.banguat.gob.gt/variables/ws/\">")
				.contains("<fechainit>01/10/2026</fechainit>").contains("<fechafin>05/10/2026</fechafin>")
				.contains("<moneda>24</moneda>");
	}

	@Test
	void elRangoDelDolarIncluyeElFinDeSemanaConElUltimoValor() throws Exception {
		guion.add(Respuesta.muestra("TipoCambioRango.xml"));

		List<CotizacionMoneda> filas = cliente().tipoCambioRango(dia(1), dia(3));

		assertThat(filas).extracting(CotizacionMoneda::fecha).containsExactly(dia(1), dia(2), dia(3));
		assertThat(filas).allSatisfy(f -> assertThat(f.moneda()).isEqualTo(2));
		assertThat(filas.get(2).venta()).isEqualByComparingTo(filas.get(1).venta()); // sabado repite el viernes
		assertThat(recibidas.getFirst().cuerpo()).contains("<TipoCambioRango ").doesNotContain("<moneda>");
	}

	@Test
	void elRangoDelEuroOmiteElFinDeSemanaYTraeVentaYCompra() throws Exception {
		guion.add(Respuesta.muestra("TipoCambioRangoMoneda.xml"));

		List<CotizacionMoneda> filas = cliente().tipoCambioRangoMoneda(dia(1), dia(5), 24);

		assertThat(filas).extracting(CotizacionMoneda::fecha).containsExactly(dia(1), dia(2), dia(5));
		CotizacionMoneda viernes = filas.get(1);
		assertThat(viernes.moneda()).isEqualTo(24);
		assertThat(viernes.venta()).isEqualByComparingTo("1.1275");
		assertThat(viernes.compra()).isEqualByComparingTo("1.1274");
	}

	@Test
	void leeLasMonedasDisponiblesConTildes() throws Exception {
		guion.add(Respuesta.muestra("VariablesDisponibles.xml"));

		List<MonedaBanguat> monedas = cliente().variablesDisponibles();

		assertThat(monedas).contains(new MonedaBanguat(2, "Dólares de EE.UU."), new MonedaBanguat(24, "Euro"),
				new MonedaBanguat(9, "Libras Esterlinas"));
		assertThat(monedas).extracting(MonedaBanguat::codigo).doesNotHaveDuplicates();
	}

	// ---------------------------------------------------------------- fallas y reintentos

	@Test
	void reintentaSiElBanguatFallaYLuegoResponde() throws Exception {
		guion.add(Respuesta.texto(503, "Servicio no disponible"));
		guion.add(Respuesta.texto(502, "Bad gateway"));
		guion.add(Respuesta.muestra("TipoCambioDia.xml"));

		TipoCambioDolar hoy = cliente().tipoCambioDia();

		assertThat(hoy.referencia()).isEqualByComparingTo("7.6414");
		assertThat(recibidas).hasSize(3);
	}

	@Test
	void despuesDeDosReintentosSeRinde() {
		guion.add(Respuesta.texto(500, "error"));

		assertThatThrownBy(() -> cliente().tipoCambioDia()).isInstanceOf(BanguatException.class)
				.hasMessageContaining("3 intentos")
				.satisfies(ex -> assertThat(((BanguatException) ex).esReintentable()).isTrue());
		assertThat(recibidas).hasSize(3);
	}

	@Test
	void unaRespuestaLentaSeCortaPorTiempoYSeReintenta() {
		guion.add(new Respuesta(200, "<x/>".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2)));

		assertThatThrownBy(() -> cliente(Duration.ofMillis(200), 1).tipoCambioDia())
				.isInstanceOf(BanguatException.class);
		assertThat(recibidas).hasSize(2);
	}

	@Test
	void unaFallaSoapNoSeReintenta() {
		guion.add(Respuesta.texto(500, "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
				+ "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
				+ "<soap:Fault><faultcode>soap:Client</faultcode><faultstring>Formato invalido</faultstring>"
				+ "</soap:Fault></soap:Body></soap:Envelope>"));

		assertThatThrownBy(() -> cliente().tipoCambioRangoMoneda(dia(1), dia(5), 24))
				.isInstanceOf(BanguatException.class).hasMessageContaining("rechazo")
				.satisfies(ex -> assertThat(((BanguatException) ex).esReintentable()).isFalse());
		assertThat(recibidas).hasSize(1);
	}

	@Test
	void unaRespuestaQueNoEsXmlNoSeReintenta() {
		guion.add(Respuesta.texto(200, "<html>mantenimiento"));

		assertThatThrownBy(() -> cliente().tipoCambioDia()).isInstanceOf(BanguatException.class);
		assertThat(recibidas).hasSize(1);
	}

	@Test
	void unaRespuestaSinFilasEsInvalida() {
		guion.add(Respuesta.texto(200, BanguatClient.sobre("TipoCambioDiaResponse", "")));

		assertThatThrownBy(() -> cliente().tipoCambioDia()).isInstanceOf(BanguatException.class)
				.hasMessageContaining("ningun valor");
	}

	@Test
	void rechazaXmlConEntidadesExternas() {
		guion.add(Respuesta.texto(200, "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
				+ "<x>&e;</x>"));

		assertThatThrownBy(() -> cliente().tipoCambioDia()).isInstanceOf(BanguatException.class)
				.hasMessageContaining("no es XML valido");
	}

	@Test
	void validaElRangoAntesDeLlamar() {
		BanguatClient cliente = cliente();

		assertThatThrownBy(() -> cliente.tipoCambioRango(dia(5), dia(1))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> cliente.tipoCambioRangoMoneda(dia(1), dia(5), 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> cliente.tipoCambioRango(null, dia(1))).isInstanceOf(IllegalArgumentException.class);
		assertThat(recibidas).isEmpty();
	}

	@Test
	void losValoresSeConservanComoDecimalesExactos() throws Exception {
		guion.add(Respuesta.muestra("TipoCambioRango.xml"));

		CotizacionMoneda viernes = cliente().tipoCambioRango(dia(1), dia(3)).get(1);

		assertThat(viernes.venta()).isEqualTo(new BigDecimal("7.64136"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "TipoCambioRango", "TipoCambioRangoMoneda" })
	void lasFilasConCamposIlegiblesNoDescartanLasCotizacionesValidasDelRango(String operacion) {
		String valida = "<Var><moneda>24</moneda><fecha>01/10/2026</fecha>"
				+ "<venta>1.1234567</venta><compra>1.11</compra></Var>";
		String numeroInvalido = valida.replace("1.1234567", "no-es-numero");
		String fechaInvalida = valida.replace("01/10/2026", "fecha-ilegible");
		String campoAusente = valida.replace("<venta>1.1234567</venta>", "");
		guion.add(Respuesta.texto(200, BanguatClient.sobre(operacion + "Response",
				numeroInvalido + valida + fechaInvalida + campoAusente)));

		List<CotizacionMoneda> filas = operacion.equals("TipoCambioRango")
				? cliente().tipoCambioRango(dia(1), dia(3)) : cliente().tipoCambioRangoMoneda(dia(1), dia(3), 24);

		assertThat(filas).containsExactly(new CotizacionMoneda(24, dia(1), new BigDecimal("1.1234567"),
				new BigDecimal("1.11")));
		assertThat(recibidas).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "TipoCambioRango", "TipoCambioRangoMoneda" })
	void sinFilasLegiblesDevuelveUnRangoVacioParaQueElServicioUseSuRespaldo(String operacion) {
		guion.add(Respuesta.texto(200, BanguatClient.sobre(operacion + "Response",
				"<Var><moneda>24</moneda><fecha>01/10/2026</fecha><venta>invalida</venta><compra>1</compra></Var>")));

		List<CotizacionMoneda> filas = operacion.equals("TipoCambioRango")
				? cliente().tipoCambioRango(dia(1), dia(3)) : cliente().tipoCambioRangoMoneda(dia(1), dia(3), 24);

		assertThat(filas).isEmpty();
		assertThat(recibidas).hasSize(1);
	}

	@Test
	void laReferenciaDiariaIlegibleSigueSiendoUnaRespuestaInvalidaSinReintentos() {
		guion.add(Respuesta.texto(200, BanguatClient.sobre("TipoCambioDiaResponse",
				"<VarDolar><fecha>01/10/2026</fecha><referencia>invalida</referencia></VarDolar>")));

		assertThatThrownBy(() -> cliente().tipoCambioDia()).isInstanceOf(BanguatException.class)
				.satisfies(ex -> assertThat(((BanguatException) ex).esReintentable()).isFalse());
		assertThat(recibidas).hasSize(1);
	}

}
