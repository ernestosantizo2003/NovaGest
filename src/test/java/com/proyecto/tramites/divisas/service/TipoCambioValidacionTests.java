package com.proyecto.tramites.divisas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.proyecto.tramites.divisas.dto.Cotizacion;
import com.proyecto.tramites.divisas.dto.CotizacionMoneda;
import com.proyecto.tramites.divisas.dto.Moneda;
import com.proyecto.tramites.divisas.entity.TipoCambio;
import com.proyecto.tramites.divisas.repository.TipoCambioRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Limites del proveedor sin red ni base; la persistencia y las carreras se prueban en TipoCambioGuardadoTests. */
@ExtendWith(MockitoExtension.class)
class TipoCambioValidacionTests {

	private static final LocalDate FECHA = LocalDate.of(2026, 10, 1);
	private static final int USD = Moneda.USD.codigoBanguat();

	@Mock
	private TipoCambioRepository tipos;

	@Mock
	private BanguatClient banguat;

	private TipoCambioService servicio;

	@BeforeEach
	void preparar() {
		servicio = new TipoCambioService(tipos, banguat,
				Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC));
	}

	private static CotizacionMoneda fila(LocalDate fecha, BigDecimal venta) {
		return new CotizacionMoneda(USD, fecha, venta, null);
	}

	static Stream<BigDecimal> ventasInvalidas() {
		return Stream.of("0", "-7.65", "0.0000004", "999999999999.9999995", "1E+999999999",
				"1E+2147483647", "1E-2147483647", "-1E+2147483647").map(BigDecimal::new);
	}

	@ParameterizedTest
	@MethodSource("ventasInvalidas")
	void unaVentaInvalidaInclusoConExponenteExtremoNoImpideGuardarLaValida(BigDecimal invalida) {
		LocalDate anterior = FECHA.minusDays(1);
		when(banguat.tipoCambioRango(any(), any())).thenReturn(List.of(fila(anterior, invalida),
				fila(FECHA, new BigDecimal("7.6543225"))));
		when(tipos.findByFechaAndMoneda(FECHA, USD))
				.thenReturn(Optional.of(new TipoCambio(FECHA, USD, new BigDecimal("7.654323"), "BANGUAT")));

		Cotizacion resultado = servicio.obtener(Moneda.USD, FECHA);

		assertThat(resultado.valor()).isEqualTo(new BigDecimal("7.654323"));
		verify(tipos).insertarSiNoExiste(FECHA, USD, new BigDecimal("7.654323"), "BANGUAT");
		verify(tipos, never()).insertarSiNoExiste(eq(anterior), anyInt(), any(), any());
	}

	@Test
	void unaFilaNulaNoImpideGuardarLasValidas() {
		when(banguat.tipoCambioRango(any(), any()))
				.thenReturn(Arrays.asList(null, fila(FECHA, new BigDecimal("7.65"))));
		when(tipos.findByFechaAndMoneda(FECHA, USD))
				.thenReturn(Optional.of(new TipoCambio(FECHA, USD, new BigDecimal("7.650000"), "BANGUAT")));

		assertThat(servicio.obtener(Moneda.USD, FECHA).valor()).isEqualTo(new BigDecimal("7.650000"));
		verify(tipos).insertarSiNoExiste(FECHA, USD, new BigDecimal("7.650000"), "BANGUAT");
	}

	@Test
	void sinVentasValidasConservaElRespaldoPersistidoComoEstimado() {
		LocalDate anterior = FECHA.minusDays(1);
		when(tipos.findFirstByMonedaAndFechaLessThanEqualOrderByFechaDesc(USD, FECHA))
				.thenReturn(Optional.of(new TipoCambio(anterior, USD, new BigDecimal("7.100000"), "BANGUAT")));
		when(banguat.tipoCambioRango(any(), any()))
				.thenReturn(List.of(fila(FECHA, new BigDecimal("0.0000004"))));

		Cotizacion resultado = servicio.obtener(Moneda.USD, FECHA);

		assertThat(resultado.fecha()).isEqualTo(anterior);
		assertThat(resultado.valor()).isEqualTo(new BigDecimal("7.100000"));
		assertThat(resultado.estimado()).isTrue();
		verify(tipos, never()).insertarSiNoExiste(any(), anyInt(), any(), any());
	}

	@ParameterizedTest
	@CsvSource({ "0.0000005,0.000001", "999999999999.9999994,999999999999.999999",
			"7.6543225,7.654323", "7.65,7.650000" })
	void unaVentaRepresentableSeInsertaConSeisDecimalesYSeRespondeDesdeLaBase(String recibida, String persistida) {
		BigDecimal valor = new BigDecimal(persistida);
		when(banguat.tipoCambioRango(any(), any())).thenReturn(List.of(fila(FECHA, new BigDecimal(recibida))));
		when(tipos.findByFechaAndMoneda(FECHA, USD))
				.thenReturn(Optional.of(new TipoCambio(FECHA, USD, valor, "BANGUAT")));

		assertThat(servicio.obtener(Moneda.USD, FECHA).valor()).isEqualTo(valor);
		verify(tipos).insertarSiNoExiste(FECHA, USD, valor, "BANGUAT");
	}

	@Test
	void paraOtraMonedaTambienDescartaVentasInvalidasSinPerderLaValida() {
		int yen = Moneda.JPY.codigoBanguat();
		when(banguat.tipoCambioRangoMoneda(any(), any(), eq(yen)))
				.thenReturn(List.of(new CotizacionMoneda(yen, FECHA.minusDays(1), new BigDecimal("1E-2147483647"), null),
						new CotizacionMoneda(yen, FECHA, new BigDecimal("150.1234565"), null)));
		BigDecimal valor = new BigDecimal("150.123457");
		when(tipos.findByFechaAndMoneda(FECHA, yen))
				.thenReturn(Optional.of(new TipoCambio(FECHA, yen, valor, "BANGUAT")));

		assertThat(servicio.obtener(Moneda.JPY, FECHA).valor()).isEqualTo(valor);
		verify(tipos).insertarSiNoExiste(FECHA, yen, valor, "BANGUAT");
		verify(tipos, never()).insertarSiNoExiste(eq(FECHA.minusDays(1)), anyInt(), any(), any());
	}

}
