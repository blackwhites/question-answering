package com.ibm.question_answering;

import java.util.List;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import com.ibm.question_answering.api.Answer;
import com.ibm.question_answering.discovery.AskDiscoveryService;
import com.ibm.question_answering.file.DocumentsReader;
import com.ibm.question_answering.maas.AskModelAsAService;
import com.ibm.question_answering.primeqa.AnswerDocument;
import com.ibm.question_answering.prompts.QuestionAnswering;
import com.ibm.question_answering.prompts.Summarization;
import com.ibm.question_answering.reranker.AskReRankerService;
import com.ibm.question_answering.reranker.DocumentScore;

@ApplicationScoped
public class QueryReRankerMaaS {
    
    @Inject
    DocumentsReader documentsReader;

    @Inject
    AskDiscoveryService askDiscoveryService;

    @Inject
    AskReRankerService askReRankerService;

    @Inject
    AskModelAsAService askMaaS;

    @Inject
    QueryPrimeAndMaaS queryPrimeAndMaaS;

    @Inject
    QuestionAnswering questionAnswering;

    @Inject
    Summarization summarization;

    @Inject
    Metrics metrics;

    public Answer query(String query) {     

        // 1. Read documents from file    
        List<com.ibm.question_answering.file.Document> documents = documentsReader.read();
        DocumentScore[] reRankerInput = documentsReader.getReRankerInput(documents);
        if ((reRankerInput == null) || (reRankerInput.length == 0)) {
            System.err.println("Couldn't read file");
            throw new RuntimeException("Couldn't read file");
        }        
        
        // 2. ReRanker
        DocumentScore[][] documentsAndScoresArray = askReRankerService.executeAndReturnRawAnswer(query, reRankerInput);
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
        //tbd
        AnswerDocument[] answerDocuments = convertToAnswerDocuments(documentsAndScores, documentsAndScores.length);
        //AnswerDocument[] answerDocuments = convertToAnswerDocuments(documentsAndScores, discoveryAnswer, documentsAndScores.length);
        if ((answerDocuments == null) || (answerDocuments.length < 1)) {
            return MockAnswers.getEmptyAnswer();
        }
        Answer output = queryPrimeAndMaaS.queryMaaS(answerDocuments, query);
        metrics.maaSStopped(output);

        return output;
    }

    //public AnswerDocument[] convertToAnswerDocuments(DocumentScore[] documentsAndScores, com.ibm.question_answering.api.Answer discoveryAnswer, int amountDocumentsLimit) {
        public AnswerDocument[] convertToAnswerDocuments(DocumentScore[] documentsAndScores, int amountDocumentsLimit) {
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
                // tbd
                //document.url = getDocumentUrl(document.document_id, discoveryAnswer);
                document.url = "";
                com.ibm.question_answering.primeqa.Answer answer = new com.ibm.question_answering.primeqa.Answer();
                answer.text = document.text; 
                answerDocument.answer = answer;
                answerDocument.document = document;
                output[index] = answerDocument;
            }
        }
        return output;
    }

    public String getDocumentUrl(String documentId, com.ibm.question_answering.api.Answer discoveryAnswer) {
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
