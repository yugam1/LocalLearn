"""locallearn — shared building blocks for the LocalLearn day scripts.

Each day imports the plumbing it needs from here and keeps only its unique
teaching content (the corpus, the queries, the breaks, the diagnoses). Import the
FEATURE MODULE, not loose names, so every call site names its source file — the
module IS the file, giving an at-a-glance link between call and definition:

    from locallearn import config, ollama, vectorstore
    settings = config.Settings.from_env()
    client   = ollama.OllamaClient(...)
    store    = vectorstore.VectorStore.connect(...)

Modules (split by FEATURE so you can review one concern at a time):

    config      Settings + .env loading (load_dotenv)
    ollama      OllamaClient (embed + chat)
    similarity  cosine (the by-hand metric a vector DB automates)
    chunking    Document / Chunk / Chunker / load_documents
    vectorstore VectorStore (Qdrant wrapper; embedder injected)
    generation  format_context + generate (grounded answer with citations)
    prompts     GROUNDED_SYSTEM / NAIVE_SYSTEM / SALES_SYSTEM
    display     banner / show_retrieval / show_chunks / print_answer
    runlog      Tee / tee_stdout (self-logging to result/)
    bm25        BM25Index / tokenize (hand-rolled lexical scoring)
    retrievers  Hit / VectorRetriever / BM25Retriever / HybridRetriever / RerankRetriever
    evaluation  GoldQuery / evaluate / sanity_check_gold (retrieval-level: did the
                right CHUNK reach top-k?)
    judging     AnswerGoldQuery / evaluate_pipeline / llm_judge / sanity_check_judge
                (answer-level: did the GENERATED ANSWER get it right?)
"""
