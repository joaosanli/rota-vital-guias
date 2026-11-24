package br.com.rotavital.serverless;

import br.com.rotavital.expedicao.negocio.aplicacao.PrepararTransferencia;
import br.com.rotavital.expedicao.negocio.dominio.PlanoRepository;
import br.com.rotavital.expedicao.negocio.dominio.PlanoTransferencia;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Funcao serverless do Rota Vital: recebe uma requisicao aprovada (POST /guias) e devolve a
 * guia de transporte do insumo.
 *
 * <p>E uma nova camada de apresentacao para o contexto de Expedicao: a regra de negocio
 * (janela de coleta, validacao da quantidade) vem do mesmo {@link PrepararTransferencia}
 * usado pela API Spring, sem copiar codigo.
 */
public class GuiaTransporteHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // Tudo que esta fora do handleRequest roda na fase de Init (cold start) e so sobrevive
    // se a plataforma reaproveitar o ambiente.
    private static int contador = 0;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PlanoRepository PLANOS = new PlanosEmMemoria();
    private static final PrepararTransferencia PREPARAR =
            new PrepararTransferencia(PLANOS, Clock.systemUTC());

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        contador++;
        Map<String, Object> pedido;
        try {
            pedido = lerBody(event);
        } catch (Exception e) {
            return resposta(400, Map.of("erro", "body precisa ser um JSON valido"));
        }
        if (!(pedido.get("requisicaoId") instanceof String requisicaoId) || requisicaoId.isBlank()) {
            return resposta(400, Map.of("erro", "requisicaoId obrigatório"));
        }
        if (Boolean.TRUE.equals(pedido.get("demorar"))) {
            dormir(12_000); // passa do Timeout de 10 s do template
        }

        String tentativaId = "T-" + requisicaoId;
        String fornecedorId = texto(pedido, "fornecedorId", "HEMOPE");
        String insumoId = texto(pedido, "insumoId", "O-NEG");
        int quantidade = pedido.get("quantidade") instanceof Number n ? n.intValue() : 1;

        if (!PREPARAR.executar(requisicaoId, tentativaId, fornecedorId, insumoId, quantidade)) {
            return resposta(422, Map.of("erro", "não foi possível preparar a transferência (quantidade inválida ou plano cancelado)"));
        }
        PlanoTransferencia plano = PLANOS.buscar(tentativaId).orElseThrow();

        Map<String, Object> guia = new LinkedHashMap<>();
        guia.put("guia", "G-" + requisicaoId);
        guia.put("requisicaoId", plano.requisicaoId());
        guia.put("hospital", pedido.get("hospital"));
        guia.put("fornecedorId", plano.fornecedorId());
        guia.put("insumoId", plano.insumoId());
        guia.put("quantidade", plano.quantidade());
        guia.put("bolsas", pedido.getOrDefault("bolsas", List.of()));
        guia.put("coletaInicio", plano.janela().inicio().toString());
        guia.put("coletaFim", plano.janela().fim().toString());
        guia.put("estado", plano.estado().name());
        guia.put("invocacaoNesteAmbiente", contador);
        return resposta(201, guia);
    }

    /** O sam local generate-event (e o API Gateway) podem mandar o body em base64. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> lerBody(APIGatewayProxyRequestEvent event) throws Exception {
        String body = event.getBody();
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        if (Boolean.TRUE.equals(event.getIsBase64Encoded())) {
            body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
        }
        return JSON.readValue(body, Map.class);
    }

    private static String texto(Map<String, Object> pedido, String campo, String padrao) {
        return pedido.get(campo) instanceof String s && !s.isBlank() ? s : padrao;
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static APIGatewayProxyResponseEvent resposta(int status, Object corpo) {
        try {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(status)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody(JSON.writeValueAsString(corpo));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Usado nos testes para simular um ambiente novo (cold start). */
    static void reiniciarAmbiente() {
        contador = 0;
        ((PlanosEmMemoria) PLANOS).limpar();
    }

    /** Repositorio em memoria: assim como o contador, so vive enquanto o ambiente vive. */
    static final class PlanosEmMemoria implements PlanoRepository {
        private final Map<String, PlanoTransferencia> planos = new ConcurrentHashMap<>();

        @Override
        public Optional<PlanoTransferencia> buscar(String tentativaId) {
            return Optional.ofNullable(planos.get(tentativaId));
        }

        @Override
        public void salvar(PlanoTransferencia plano) {
            planos.put(plano.tentativaId(), plano);
        }

        void limpar() {
            planos.clear();
        }
    }
}
