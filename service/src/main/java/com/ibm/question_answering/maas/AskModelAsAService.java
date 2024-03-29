package com.ibm.question_answering.maas;

import java.util.ArrayList;
import java.util.Optional;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.ibm.question_answering.Metrics;
import com.ibm.question_answering.api.DocumentPassage;
import com.ibm.question_answering.api.PassageAnswer;
import com.ibm.question_answering.api.Result;
import com.ibm.question_answering.primeqa.AnswerDocument;
import com.ibm.question_answering.primeqa.AskPrimeQA;
import com.ibm.question_answering.prompts.QuestionAnswering;
import com.ibm.question_answering.proxy.ProxyExceptionMapper;
import com.ibm.question_answering.proxy.ProxyServiceResource;

@ApplicationScoped
public class AskModelAsAService {
    public AskModelAsAService() {}

    @Inject
    Metrics metrics;

    @Inject
    AskPrimeQA askPrimeQA;
    
    @Inject
    QuestionAnswering questionAnswering;
    
    @Inject
    ModelAsAServiceResource maasResource;

    @Inject
    ProxyServiceResource proxyResource;

    final String PROXY_API_KEY_NOT_SET = "NOT_SET"; 
    String proxyApiKey = System.getenv("PROXY_API_KEY");
    private boolean useProxy = false;

    final String PROXY_URL_NOT_SET = "NOT_SET"; 
    private String proxyUrl = System.getenv("PROXY_URL");
    final static String ERROR_PROXY_URL_NOT_SET = ProxyExceptionMapper.ERROR_PROXY_PREFIX + "PROXY_URL not defined";

    final String MAAS_URL_NOT_SET = "NOT_SET";   
    @ConfigProperty(name = "MAAS_URL", defaultValue = MAAS_URL_NOT_SET) 
    private String url;
    final static String ERROR_MAAS_URL_NOT_SET = MaaSExceptionMapper.ERROR_MAAS_PREFIX + "MAAS_URL not defined";

    final String MAAS_API_KEY_NOT_SET = "NOT_SET";   
    @ConfigProperty(name = "MAAS_API_KEY", defaultValue = MAAS_API_KEY_NOT_SET) 
    private String apiKey;
    final static String ERROR_MAAS_API_KEY_NOT_SET = MaaSExceptionMapper.ERROR_MAAS_PREFIX + "MAAS_API_KEY not defined";

    final static String MAAS_LLM_NAME = "google/flan-t5-xxl";
    @ConfigProperty(name = "EXPERIMENT_LLM_NAME") 
    Optional<String> llmNameOptionalString;

    final static int MAAS_LLM_MIN_NEW_TOKENS = 1;
    @ConfigProperty(name = "EXPERIMENT_LLM_MIN_NEW_TOKENS") 
    Optional<String> llmMinNewTokensOptionalString;

    final static int MAAS_LLM_MAX_NEW_TOKENS = 300;
    @ConfigProperty(name = "EXPERIMENT_LLM_MAX_NEW_TOKENS") 
    Optional<String> llmMaxNewTokensOptionalString;

    final static int MAAS_LLM_MAX_INPUT_DOCUMENTS = 3;
    @ConfigProperty(name = "EXPERIMENT_LLM_MAX_INPUT_DOCUMENTS") 
    Optional<String> llmMaxInputDocumentsOptionalString;

    final static int MAX_RESULTS = 5;
    @ConfigProperty(name = "MAX_RESULTS") 
    Optional<String> maxResultsOptionalString;
    int maxResults = MAX_RESULTS;

    public com.ibm.question_answering.api.Answer execute(String query, AnswerDocument[] answerDocuments) {      
        if (maxResultsOptionalString.isPresent()) {
            try {
                maxResults = Integer.parseInt(maxResultsOptionalString.get());
            } catch (Exception e) {}
        }  
        int llmMaxInputDocuments = MAAS_LLM_MAX_INPUT_DOCUMENTS;
        if (llmMaxInputDocumentsOptionalString.isPresent()) {
            try {
                llmMaxInputDocuments = Integer.parseInt(llmMaxInputDocumentsOptionalString.get());
            } catch (Exception e) {}
        }
        metrics.setMaaSMaxAmountDocuments(llmMaxInputDocuments);
        if (llmMaxInputDocuments < answerDocuments.length) {
            AnswerDocument[] answerDocumentsOrg = answerDocuments;
            answerDocuments = new AnswerDocument[llmMaxInputDocuments];
            System.arraycopy(answerDocumentsOrg, 0, answerDocuments, 0, llmMaxInputDocuments);
        }
        String prompt = questionAnswering.getPrompt(query, answerDocuments);
        com.ibm.question_answering.api.Answer output = execute(prompt);
        output = cleanUpAnswer(output, answerDocuments);
        return output;
    }

    public com.ibm.question_answering.api.Answer execute(String prompt) {
        if ((proxyApiKey != null) && (!proxyApiKey.equals(""))) {
            if (!proxyApiKey.equalsIgnoreCase(PROXY_API_KEY_NOT_SET)) {
                useProxy = true;
                if ((proxyUrl == null) || (proxyUrl.equals("")) || (proxyUrl.equalsIgnoreCase(PROXY_URL_NOT_SET))) {
                    System.err.println(ERROR_PROXY_URL_NOT_SET);
                    throw new RuntimeException(ERROR_PROXY_URL_NOT_SET);
                }
            }
        }
        if (url.equalsIgnoreCase(MAAS_URL_NOT_SET)) {
            System.err.println(ERROR_MAAS_URL_NOT_SET);
            throw new RuntimeException(ERROR_MAAS_URL_NOT_SET);
        }
        if (apiKey.equalsIgnoreCase(MAAS_API_KEY_NOT_SET)) {
            System.err.println(ERROR_MAAS_API_KEY_NOT_SET);
            throw new RuntimeException(ERROR_MAAS_API_KEY_NOT_SET);
        }
        int llmMinNewTokens = MAAS_LLM_MIN_NEW_TOKENS;
        if (llmMinNewTokensOptionalString.isPresent()) {
            try {
                llmMinNewTokens = Integer.parseInt(llmMinNewTokensOptionalString.get());
            } catch (Exception e) {}
        }
        int llmMaxNewTokens = MAAS_LLM_MAX_NEW_TOKENS;
        if (llmMaxNewTokensOptionalString.isPresent()) {
            try {
                llmMaxNewTokens = Integer.parseInt(llmMaxNewTokensOptionalString.get());
            } catch (Exception e) {}
        }        
        String llmName = MAAS_LLM_NAME;
        if (llmNameOptionalString.isPresent()) {
            llmName = llmNameOptionalString.get();
        }        

        com.ibm.question_answering.maas.Parameters parameters = new com.ibm.question_answering.maas.Parameters();
        parameters.min_new_tokens = llmMinNewTokens;
        parameters.max_new_tokens = llmMaxNewTokens;
        parameters.temperature = 0;
        metrics.maaSStarted(llmMinNewTokens, llmMaxNewTokens, llmName, prompt);
        com.ibm.question_answering.api.Answer output;
        output = new com.ibm.question_answering.api.Answer(true, 0, null);
        String[] inputs = new String[1];
        inputs[0] = prompt;

        Answer response;
        if (useProxy == false) {
            response = maasResource.ask(new Input(llmName, inputs, parameters));
        }
        else {
            com.ibm.question_answering.proxy.Input proxyInput = new com.ibm.question_answering.proxy.Input(apiKey, url, 
                new com.ibm.question_answering.maas.Input(llmName, inputs, parameters));
            response = proxyResource.ask(proxyInput);
        }
        
        if (response != null) {
            if (response.results.length > 0) {
                String generatedText = response.results[0].generated_text;

                // special case
                String EVIDENCE_MARKER1 = "; evidence:";
                String EVIDENCE_MARKER2 = ". evidence:";
                String EVIDENCE_MARKER3 = "? evidence:";
                String RESPONSE_MARKER = "response: ";
                if (generatedText.contains(EVIDENCE_MARKER1)) {
                    generatedText = generatedText.substring(RESPONSE_MARKER.length(), generatedText.indexOf(EVIDENCE_MARKER1));
                }
                if (generatedText.contains(EVIDENCE_MARKER2)) {
                    generatedText = generatedText.substring(0, generatedText.indexOf(EVIDENCE_MARKER2) + 1);
                }
                if (generatedText.contains(EVIDENCE_MARKER3)) {
                    generatedText = generatedText.substring(0, generatedText.indexOf(EVIDENCE_MARKER3) + 1);
                }

                output.matching_results = 1;
                ArrayList<Result> results = new ArrayList<Result>();
                String text[] = new String[1];
                text[0] = generatedText;
                results.add(new Result(Result.TITLE_ONE_ANSWER, 
                    Result.TITLE_ONE_ANSWER,
                    text,
                    null,
                    null));
                output.results = results;
            }
        }
        return output;
    }

    public String removeEverythingAfterLastDot(String answer) {
        String output = answer;
        int lastIndexOfDot = answer.lastIndexOf(".");
        if (lastIndexOfDot != -1) {
            output = answer.substring(0, lastIndexOfDot + 1);
            output = output.trim();
        }
        return output;
    }

    public com.ibm.question_answering.api.Answer cleanUpAnswer(com.ibm.question_answering.api.Answer output, AnswerDocument[] answerDocuments) {
        if (answerDocuments != null) {
            output.matching_results = answerDocuments.length;
            ArrayList<Result> results = new ArrayList<Result>();
            results.add(output.results.get(0));
            for (int index = 0; index < answerDocuments.length; index++) {
                results.add(askPrimeQA.getAnswerDocument(answerDocuments[index]));
            }
            output.results = results;
        }

        int results = output.results.size();
        if (maxResults + 1 < results) {
            for (int index = results - 1; index > maxResults; index--) {
                output.results.remove(index);
            }
        }
        for (int index = 0; index < output.results.size(); index++) {
            if (output.results.get(index).document_passages != null) {
                int countPassages = output.results.get(index).document_passages.length;
                if (countPassages > 0) {
                    String textRead = output.results.get(index).document_passages[0].passage_text;
                    DocumentPassage[] documentPassages = new DocumentPassage[1];
                    PassageAnswer[] passageAnswers = new PassageAnswer[1];
                    passageAnswers[0] = new PassageAnswer(textRead, 0);
                    passageAnswers[0].field = PassageAnswer.FIELD_SUMMARY;
                    documentPassages[0] = new DocumentPassage("<em>IBM</em> <em>acquires</em> <em>Red</em> <em>Hat</em>", DocumentPassage.FIELD_TEXT, passageAnswers);
                    String text[] = new String[1];
                    text[0] = textRead;
                    output.results.get(index).document_passages = documentPassages;                    
                } 
            }
        }

        /*
        String answerAsText = output.results.get(0).text.text[0];
        answerAsText = removeEverythingAfterLastDot(answerAsText);
        String[] text = new String[1];
        text[0] = answerAsText;
        output.results.get(0).text.text = text;
        */

        return output;
    }
}
