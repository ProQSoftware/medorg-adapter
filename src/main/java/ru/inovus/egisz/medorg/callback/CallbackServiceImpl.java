package ru.inovus.egisz.medorg.callback;

import org.apache.cxf.annotations.SchemaValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.inovus.egisz.medorg.exceptions.NotDeliveredException;
import ru.inovus.egisz.medorg.rest.RestCallbackCommand;
import ru.inovus.egisz.medorg.rest.XmlResultContent;
import ru.inovus.egisz.medorg.service.JmsMessageExtractorBean;
import ru.inovus.egisz.medorg.util.HttpRequester;
import ru.inovus.egisz.medorg.util.XmlHelper;
import ru.rt.eu.nr.autogenerated.callback.mis.service.v1_0.Callback;

import javax.annotation.Resource;
import javax.ejb.EJB;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.Queue;
import javax.jws.HandlerChain;
import javax.jws.WebMethod;
import javax.jws.WebResult;
import javax.jws.WebService;
import javax.xml.ws.BindingType;
import javax.xml.ws.WebServiceContext;
import javax.xml.ws.soap.SOAPBinding;
import java.net.HttpURLConnection;

/**
 * Обеспечивает получение результирующих сообщений из ЕГИСЗ ИПС
 */
@SchemaValidation
@WebService(name = "Callback", targetNamespace = "http://emu.callback.mis.service.nr.eu.rt.ru/", serviceName = "callback",
        wsdlLocation = "/ru/rt/eu/nr/callback/mis/service/v1.0.wsdl")
@HandlerChain(file = "handler-chain.xml")
@BindingType(value = SOAPBinding.SOAP12HTTP_BINDING)
public class CallbackServiceImpl implements Callback {

    private static final Logger logger = LoggerFactory.getLogger(CallbackServiceImpl.class);

    @EJB
    private JmsMessageExtractorBean jmsMessageExtractor;

    @Resource(lookup = "java:jboss/exported/jms/queue/RestCallbackQueue")
    private Queue restCallbackQueue;

    @Resource
    WebServiceContext context;

    /**
     * Обрабатывает полученное результирующее сообщение из ЕГИСЗ
     *
     * @param id       идентификатор принятого сообщения
     * @param oid      идентификатор базового объекта
     * @param response документ, кодированный в base64, который содержит результат обработки сообщения
     */
    @Override
    @WebMethod(action = "sendResponse")
    @WebResult(name = "status", targetNamespace = "")
    public int sendResponse(String id, String oid, String response) {

        int result = 0; //со стороны ИПС просили всегда возвращать 0

        try {

            logger.debug("MEDORG. Предпринимается попытка выполнения обработки результирующего сообщения ЕГИСЗ ИПС для id принятого сообщения {}", id);

            String restCallbackUrl = getRestCallbackUrl(id);

            logger.debug("MEDORG. Извлечено из очереди queue/ReplierQueue restCallbackUrl: {}  для id принятого сообщения {}", restCallbackUrl, id);

            final String data = getResultData(id, oid, response);

            logger.debug("MEDORG. Подготовлено к отправке потребителю {} результирующее сообщение: {}", restCallbackUrl, response);

            int responseCode = HttpRequester.post(restCallbackUrl, data);

            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_ACCEPTED) {

                logger.debug("MEDORG. Доставлено потребителю результирующее сообщение по адресу {}. Тело запроса: {}. HTTP-статус ответа: {}.", restCallbackUrl, data, responseCode);

            } else {

                throw new NotDeliveredException("HTTP-статус полученного сообщения от потребителя: " + responseCode);
            }

        } catch (Exception ex) {

            logger.error("MEDORG. Ошибка при попытке передачи в обработку результирующего сообщения ЕГИСЗ ИПС для id принятого сообщения {}: oid={}, response={}", id, oid, response, ex);
        }

        return result;
    }

    /**
     * Возвращает restCallbackUrl для id принятого сообщения ЕГИСЗ ИПС
     *
     * @param egiszRespMessageId id принятого сообщения ЕГИСЗ ИПС
     * @throws RuntimeException
     * @return
     */
    private String getRestCallbackUrl(final String egiszRespMessageId)  {

        final Message jmsMessage = jmsMessageExtractor.getMessage(egiszRespMessageId, restCallbackQueue);

        if(jmsMessage == null){
            throw new RuntimeException("Не удалось найти привязку к restCallbackUrl в очереди queue/RestCallbackQueue для id принятого сообщения ЕГИСЗ ИПС: " + egiszRespMessageId);
        }

        String result;

        try {

            RestCallbackCommand command = jmsMessage.getBody(RestCallbackCommand.class);

            result = command.getCallbackUrl();

        } catch (JMSException e) {
            throw new RuntimeException("Не удалось извлечь restCallbackUrl из JMS-сообщения queue/RestCallbackQueue для id принятого сообщения ЕГИСЗ ИПС: "+ egiszRespMessageId +".", e);
        }

        return result;
    }

    /**
     * Возвращает тело запроса потребителю результирующего сообщения ЕГИСЗ
     *
     * @param egiszRespMessageId id принятого сообщения ЕГИСЗ
     * @param oid      идентификатор базового объекта
     * @param response документ, кодированный в base64, который содержит результат обработки сообщения
     * @return
     * @throws RuntimeException
     */
    private String getResultData(final String egiszRespMessageId, String oid, String response) {

        try {

            final XmlResultContent content = new XmlResultContent(egiszRespMessageId, oid, response);

            return XmlHelper.instanceToString(content, XmlResultContent.class);

        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
