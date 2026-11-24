package br.com.rotavital.serverless;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiaTransporteHandlerTest {

    private final GuiaTransporteHandler handler = new GuiaTransporteHandler();
    private final ObjectMapper json = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @BeforeEach
    void ambienteNovo() {
        GuiaTransporteHandler.reiniciarAmbiente();
    }

    private APIGatewayProxyRequestEvent evento(String body, boolean base64) {
        var e = new APIGatewayProxyRequestEvent().withHttpMethod("POST").withPath("/guias");
        if (base64) {
            return e.withBody(Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)))
                    .withIsBase64Encoded(true);
        }
        return e.withBody(body).withIsBase64Encoded(false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> corpo(APIGatewayProxyResponseEvent r) throws Exception {
        return json.readValue(r.getBody(), Map.class);
    }

    @Test
    void geraGuiaComJanelaDeColetaDoDominioDeExpedicao() throws Exception {
        var r = handler.handleRequest(evento("{\"requisicaoId\":\"R-002\",\"hospital\":\"H-Caruaru\",\"quantidade\":2}", false), null);
        assertEquals(201, r.getStatusCode());
        var guia = corpo(r);
        assertEquals("G-R-002", guia.get("guia"));
        assertEquals(2, guia.get("quantidade"));
        assertEquals("PREPARADO", guia.get("estado"));
        var inicio = Instant.parse((String) guia.get("coletaInicio"));
        var fim = Instant.parse((String) guia.get("coletaFim"));
        assertEquals(Duration.ofHours(2), Duration.between(inicio, fim)); // regra do PrepararTransferencia
    }

    @Test
    void aceitaBodyEmBase64() throws Exception {
        var r = handler.handleRequest(evento("{\"requisicaoId\":\"R-001\",\"bolsas\":[\"O-NEG-0142\"]}", true), null);
        assertEquals(201, r.getStatusCode());
        assertEquals(java.util.List.of("O-NEG-0142"), corpo(r).get("bolsas"));
    }

    @Test
    void semRequisicaoIdRetorna400() {
        assertEquals(400, handler.handleRequest(evento("{\"hospital\":\"H-Recife\"}", false), null).getStatusCode());
    }

    @Test
    void quantidadeInvalidaRetorna422() {
        assertEquals(422, handler.handleRequest(evento("{\"requisicaoId\":\"R-009\",\"quantidade\":0}", false), null).getStatusCode());
    }

    @Test
    void contadorCresceNoMesmoAmbiente() throws Exception {
        var e = evento("{\"requisicaoId\":\"R-002\"}", false);
        handler.handleRequest(e, null);
        assertEquals(2, corpo(handler.handleRequest(e, null)).get("invocacaoNesteAmbiente"));
    }

    @Test
    void processaOEventoGeradoPeloSam() throws Exception {
        Path arquivo = Path.of("../events/pedido.json");
        // no sam build o codigo e copiado para uma pasta temporaria, sem a pasta events/
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(arquivo));
        var evento = json.readValue(Files.readString(arquivo), APIGatewayProxyRequestEvent.class);
        var r = handler.handleRequest(evento, null);
        assertEquals(201, r.getStatusCode());
        assertTrue(r.getBody().contains("G-R-001"));
    }
}
