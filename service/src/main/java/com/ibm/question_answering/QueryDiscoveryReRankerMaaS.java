package com.ibm.question_answering;

import java.util.Optional;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.ibm.question_answering.discovery.AskDiscoveryService;
import com.ibm.question_answering.discovery.RelevantOutput;
import com.ibm.question_answering.maas.AskModelAsAService;
import com.ibm.question_answering.primeqa.AnswerDocument;
import com.ibm.question_answering.prompts.QuestionAnswering;
import com.ibm.question_answering.prompts.Summarization;
import com.ibm.question_answering.proxy.AskProxyService;
import com.ibm.question_answering.reranker.AskReRankerService;
import com.ibm.question_answering.reranker.Document;
import com.ibm.question_answering.reranker.DocumentScore;

@ApplicationScoped
public class QueryDiscoveryReRankerMaaS {
    
    @Inject
    AskDiscoveryService askDiscoveryService;

    @Inject
    AskReRankerService askReRankerService;

    @Inject
    AskModelAsAService askMaaS;

    @Inject
    AskProxyService askProxy;

    @Inject
    QueryPrimeAndMaaS queryPrimeAndMaaS;

    @Inject
    QuestionAnswering questionAnswering;

    @Inject
    Summarization summarization;

    static final int LLM_MAX_INPUT_DOCUMENTS = 5;
    @ConfigProperty(name = "experiment.llm-max-input-documents") 
    Optional<String> llmMaxInputDocumentsOptionalString;

    @Inject
    Metrics metrics;

    public Answer query(String query, boolean proxy, boolean summaries) {
        int llmMaxInputDocuments = LLM_MAX_INPUT_DOCUMENTS;
        if (llmMaxInputDocumentsOptionalString.isPresent()) {
            try {
                llmMaxInputDocuments = Integer.parseInt(llmMaxInputDocumentsOptionalString.get());
            } catch (Exception e) {}
        }
        
        // 1. Discovery
        com.ibm.question_answering.Answer discoveryAnswer = askDiscoveryService.ask(query);   
        if (discoveryAnswer == null) {
            System.err.println(com.ibm.question_answering.discovery.DiscoveryExceptionMapper.ERROR_DISCOVERY_UNEXPECTED);
            throw new RuntimeException(com.ibm.question_answering.discovery.DiscoveryExceptionMapper.ERROR_DISCOVERY_UNEXPECTED);
        }        
        
        // 2. ReRanker
        int inputReRankerAmountDocuments = discoveryAnswer.results.size();
        DocumentScore[] documentsAndScoresInput = new DocumentScore[inputReRankerAmountDocuments];
        for (int index = 0; index < inputReRankerAmountDocuments; index++) {
            Document document = new Document();
            document.text = RelevantOutput.getDiscoveryResultAsText(discoveryAnswer, index);
            document.document_id = discoveryAnswer.results.get(index).document_id;
            document.title = discoveryAnswer.results.get(index).title;
            documentsAndScoresInput[index] = new DocumentScore(document, discoveryAnswer.results.get(index).result_metadata.confidence);            
        }
        DocumentScore[][] documentsAndScoresArray = askReRankerService.executeAndReturnRawAnswer(query, documentsAndScoresInput);
        if ((documentsAndScoresArray == null) || (documentsAndScoresArray.length < 1)) {
            System.err.println(com.ibm.question_answering.reranker.ReRankerExceptionMapper.ERROR_RERANKER_UNEXPECTED);
            throw new RuntimeException(com.ibm.question_answering.reranker.ReRankerExceptionMapper.ERROR_RERANKER_UNEXPECTED);
        }
        DocumentScore[] documentsAndScores = documentsAndScoresArray[0];
        if ((documentsAndScores == null) || (documentsAndScores.length < 1)) {
            System.err.println(com.ibm.question_answering.reranker.ReRankerExceptionMapper.ERROR_RERANKER_UNEXPECTED);
            throw new RuntimeException(com.ibm.question_answering.reranker.ReRankerExceptionMapper.ERROR_RERANKER_UNEXPECTED);
        }

        // 3. MaaS
        metrics.setMaaSMaxAmountDocuments(llmMaxInputDocuments);
        AnswerDocument[] answerDocuments = convertToAnswerDocuments(documentsAndScores, discoveryAnswer, llmMaxInputDocuments);
        if ((answerDocuments == null) || (answerDocuments.length < 1)) {
            return MockAnswers.getEmptyAnswer();
        }

        Answer output = queryPrimeAndMaaS.queryMaaS(answerDocuments, query, proxy, summaries);
        String answerAsText = output.results.get(0).text.text[0];
        //answerAsText = removeEverythingAfterLastDot(answerAsText);
        String[] text = new String[1];
        text[0] = answerAsText;
        output.results.get(0).text.text = text;
        metrics.maaSStopped(output);

        return output;
    }

    public AnswerDocument[] convertToAnswerDocuments(DocumentScore[] documentsAndScores, com.ibm.question_answering.Answer discoveryAnswer, int amountDocumentsLimit) {
        AnswerDocument[] output = null;
        if (documentsAndScores != null) {
            int amount = documentsAndScores.length;
            if (amount > amountDocumentsLimit) {
                amount = amountDocumentsLimit;
            }
            output = new AnswerDocument[amount];
            for (int index = 0; index < amount; index++) {
                AnswerDocument answerDocument = new AnswerDocument();
                com.ibm.question_answering.primeqa.Document document = new com.ibm.question_answering.primeqa.Document();
                document.score = documentsAndScores[index].score;
                document.text = documentsAndScores[index].document.text;
                document.title = documentsAndScores[index].document.title;
                document.document_id = documentsAndScores[index].document.document_id;
                document.url = getDocumentUrl(document.document_id, discoveryAnswer);
                com.ibm.question_answering.primeqa.Answer answer = new com.ibm.question_answering.primeqa.Answer();
                answer.text = document.text; 
                answerDocument.answer = answer;
                answerDocument.document = document;
                output[index] = answerDocument;
            }
        }
        return output;
    }

    public String getDocumentUrl(String documentId, com.ibm.question_answering.Answer discoveryAnswer) {
        String output = "";
        for (int index = 0; index < discoveryAnswer.results.size(); index++) {
            if (discoveryAnswer.results.get(index).document_id.equals(documentId)) {
                output = discoveryAnswer.results.get(index).url;
            }
        }
        return output;
    }

    // Input: "In the Purchasing Details tab, you can find detailed purchasing spend information. Question:"
    // Output: "In the Purchasing Details tab, you can find detailed purchasing spend information."
    public String removeEverythingAfterLastDot(String answer) {
        String output = answer;
        int lastIndexOfDot = answer.lastIndexOf(".");
        if (lastIndexOfDot != -1) {
            output = answer.substring(0, lastIndexOfDot + 1);
            output = output.trim();
        }
        return output;
    }
}
