package org.hyperledger.besu.riscv.poc.evm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.config.GenesisConfig;
import org.hyperledger.besu.consensus.merge.PostMergeContext;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.BadBlockManager;
import org.hyperledger.besu.ethereum.chain.GenesisState;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.chain.DefaultBlockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Difficulty;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.mainnet.MainnetProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockHeaderFunctions;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.storage.StorageProvider;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStoragePrefixedKeyBlockchainStorage;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStorageProvider;
import org.hyperledger.besu.ethereum.storage.keyvalue.VariablesKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.BonsaiWorldStateProvider;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache.CodeCache;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache.NoopBonsaiCachedMerkleTrieLoader;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.pathbased.common.provider.WorldStateQueryParams;
import org.hyperledger.besu.ethereum.worldstate.DataStorageConfiguration;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.ServiceManager;
import org.hyperledger.besu.plugin.services.BesuService;
import org.hyperledger.besu.services.kvstore.InMemoryKeyValueStorage;
import org.hyperledger.besu.services.kvstore.SegmentedInMemoryKeyValueStorage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.hyperledger.besu.ethereum.trie.pathbased.common.storage.PathBasedWorldStateKeyValueStorage.WORLD_BLOCK_HASH_KEY;
import static org.hyperledger.besu.ethereum.trie.pathbased.common.storage.PathBasedWorldStateKeyValueStorage.WORLD_ROOT_HASH_KEY;

public class BlockRunner {

    private final ProtocolSchedule protocolSchedule;
    private final MutableBlockchain blockchain;
    private final org.hyperledger.besu.ethereum.trie.pathbased.bonsai.BonsaiWorldStateProvider worldStateArchive;
    private final ProtocolContext protocolContext;

public static BlockRunner create(final List<BlockHeader> prevHeaders, final Map<Hash,Bytes> trienodes, final Map<Hash,Bytes> codes) {

        final GenesisConfig genesisConfig = GenesisConfig.fromSource(GenesisConfig.class.getResource("/hoodi.json"));

        final NoOpMetricsSystem noOpMetricsSystem = new NoOpMetricsSystem();

        EvmConfiguration evmConfiguration = new EvmConfiguration(32_000L, EvmConfiguration.WorldUpdaterMode.JOURNALED);

        ProtocolSchedule protocolSchedule = MainnetProtocolSchedule.fromConfig(
                genesisConfig.getConfigOptions(),
            evmConfiguration,
                MiningConfiguration.MINING_DISABLED,
                new BadBlockManager(),
                false,
                false,
                noOpMetricsSystem
        );

        // Genesis State
        GenesisState genesisState = GenesisState.fromConfig(genesisConfig, protocolSchedule, new CodeCache());

        StorageProvider storageProvider = new KeyValueStorageProvider(
                segments -> new SegmentedInMemoryKeyValueStorage(),
                new InMemoryKeyValueStorage(),
                noOpMetricsSystem
        );

        var variablesStorage = new VariablesKeyValueStorage(
                storageProvider.getStorageBySegmentIdentifier(KeyValueSegmentIdentifier.VARIABLES)
        );
        var blockchainStorage = new KeyValueStoragePrefixedKeyBlockchainStorage(
                storageProvider.getStorageBySegmentIdentifier(KeyValueSegmentIdentifier.BLOCKCHAIN),
                variablesStorage,
                new MainnetBlockHeaderFunctions(),
                false
        );

        MutableBlockchain blockchain = DefaultBlockchain.createMutable(
                genesisState.getBlock(),
                blockchainStorage,
                noOpMetricsSystem,
                0
        );

        final KeyValueStoragePrefixedKeyBlockchainStorage.Updater updater = blockchainStorage.updater();
        prevHeaders.forEach(blockHeader -> {
            updater.putBlockHeader(blockHeader.getBlockHash(), blockHeader);
            updater.putBlockHash(blockHeader.getNumber(), blockHeader.getBlockHash());
            updater.setChainHead(blockHeader.getBlockHash());
            updater.putTotalDifficulty(blockHeader.getBlockHash(), Difficulty.ZERO);
        });
        updater.commit();

        // WorldState
        var bonsaiStorage = new StatelessBonsaiWorldStateLayerStorage(
                storageProvider,
                noOpMetricsSystem,
                DataStorageConfiguration.DEFAULT_BONSAI_PARTIAL_DB_CONFIG
        );

        ServiceManager serviceManager = new ServiceManager() {
            @Override
            public <T extends BesuService> void addService(Class<T> serviceType, T service) {
                //no nop
               }

            @Override
            public <T extends BesuService> Optional<T> getService(Class<T> serviceType) {
                return Optional.empty();
            }
        };

        final BonsaiWorldStateKeyValueStorage.Updater stateUpdater = bonsaiStorage.updater();
        trienodes.forEach((hash, value) -> {
            stateUpdater.getWorldStateTransaction().put(KeyValueSegmentIdentifier.TRIE_BRANCH_STORAGE,hash.toArray(),value.toArray());
        });
        codes.forEach((hash, value) -> {
            stateUpdater.getWorldStateTransaction().put(KeyValueSegmentIdentifier.CODE_STORAGE, hash.toArrayUnsafe(),value.toArrayUnsafe());
        });
        final BlockHeader head = prevHeaders.get(prevHeaders.size() - 1);
        stateUpdater. getWorldStateTransaction().put(KeyValueSegmentIdentifier.TRIE_BRANCH_STORAGE, WORLD_ROOT_HASH_KEY,head.getStateRoot().toArray());
        stateUpdater.getWorldStateTransaction().put(KeyValueSegmentIdentifier.TRIE_BRANCH_STORAGE, WORLD_BLOCK_HASH_KEY,head.getBlockHash().toArray());
        stateUpdater.commit();

        var worldStateArchive = new BonsaiWorldStateProvider(
                bonsaiStorage,
                blockchain,
                Optional.empty(), // maxLayersToLoad
                new NoopBonsaiCachedMerkleTrieLoader(),
                serviceManager,
                evmConfiguration,
                () -> (__, ___) -> {},
                new CodeCache()
        );

        /*
        curl --location 'http://127.0.0.1:8545' \
        --data '{
            "jsonrpc": "2.0",
            "method": "debug_traceTransaction",
            "params": [
                "0xca36c1e529714639c25fc2ac9a3b13c4d0a3e81a74f451313ec2926c54cc48f3",
                {
                    "disableStorage": true
                }
            ],
            "id": 1
        }'
         */
        var postMergeContext = new PostMergeContext();

        var protocolContext = new ProtocolContext.Builder()
                .withBlockchain(blockchain)
                .withWorldStateArchive(worldStateArchive)
                .withConsensusContext(postMergeContext)
                .withBadBlockManager(new BadBlockManager())
                .withServiceManager(serviceManager)
                .build();

        return new BlockRunner(protocolSchedule,protocolContext, blockchain, worldStateArchive);
    }

    private BlockRunner(
            ProtocolSchedule protocolSchedule,
            ProtocolContext protocolContext,
            MutableBlockchain blockchain,
            BonsaiWorldStateProvider worldStateArchive) {
        this.protocolSchedule = protocolSchedule;
        this.protocolContext = protocolContext;
        this.blockchain = blockchain;
        this.worldStateArchive = worldStateArchive;
    }

    public void processBlock(final Block block) {
        BlockHeader parentHeader = blockchain.getBlockHeader(block.getHeader().getParentHash())
                .orElseThrow(() -> new RuntimeException("Parent block not found"));

        MutableWorldState worldState = worldStateArchive.getWorldState(WorldStateQueryParams.newBuilder().withBlockHeader(parentHeader).withShouldWorldStateUpdateHead(true)
                        .build())
                .orElseThrow(() -> new RuntimeException("Cannot get mutable world state"));

        ProtocolSpec protocolSpec = protocolSchedule.getByBlockHeader(block.getHeader());

        var result = protocolSpec.getBlockProcessor().processBlock(
                protocolContext,
                blockchain,
                worldState,
                block
        );

        if (!result.isSuccessful()) {
            System.out.println("Block " + block.getHeader().getNumber()
                    + " failed with error message "+ result.errorMessage);
            throw new RuntimeException("Block processing failed");
        }

        blockchain.appendBlock(block, result.getReceipts());

        System.out.println("✓ Block " + block.getHeader().getNumber() + " processed successfully");
        System.out.println("  Transactions: " + block.getBody().getTransactions().size());
        System.out.println("  Receipts: " + result.getReceipts().size());
        System.out.println("  Gas used: " + block.getHeader().getGasUsed());
        System.out.println("  Stateroot: " + result.getYield().get().getWorldState().rootHash());
    }

    private final static ExecutionWitnessJson EXECUTION_WITNESS;
    private final static Block BLOCK_TO_IMPORT;
    static {
            ObjectMapper objectMapper = new ObjectMapper();

            try {
                /*
                    curl --location 'http://127.0.0.1:8545' --data '{
                        "jsonrpc": "2.0",
                        "method": "debug_executionWitness",
                        "params": [
                            "0xC9B0"
                        ],
                        "id": 1
                    }'
                 */
                EXECUTION_WITNESS = objectMapper.readValue(
                        Files.readString(Path.of(BlockRunner.class.getResource("/state.json").toURI())),
                        ExecutionWitnessJson.class);
                System.out.println("✓ Loaded execution witness");
                System.out.println("  State: " + EXECUTION_WITNESS.getState().size());
                System.out.println("  Keys: " + EXECUTION_WITNESS.getKeys().size());
                System.out.println("  Codes: " + EXECUTION_WITNESS.getCodes().size());
                System.out.println("  Headers: " + EXECUTION_WITNESS.getHeaders().size());

                 /*
                    curl --location 'http://127.0.0.1:8545' --data '{
                        "jsonrpc": "2.0",
                        "method": "debug_getRawBlock",
                        "params": [
                            "0xC9B0"
                        ],
                        "id": 1
                    }'
                 */
                BLOCK_TO_IMPORT = Block.readFrom(RLP.input(Bytes.fromHexString(Files.readString(Path.of(BlockRunner.class.getResource("/block.rlp").toURI()))))
                        , new MainnetBlockHeaderFunctions());
                System.out.println("✓ Loaded block to import "+BLOCK_TO_IMPORT.getHeader().getNumber());
            } catch (Exception e) {
                throw new RuntimeException("Unable to load the state ", e);
            }
    }
    
    public static void main(final String[] args) {
        System.out.println("Starting BlockRunner .");
        final Map<Hash, Bytes> trieNodes = EXECUTION_WITNESS.getState().stream()
                .map(Bytes::fromHexString)
                .collect(Collectors.toMap(
                        Hash::hash,
                        o -> o
                ));
        final Map<Hash, Bytes> codes = EXECUTION_WITNESS.getCodes().stream()
                .map(Bytes::fromHexString)
                .collect(Collectors.toMap(
                        Hash::hash,
                        o -> o
                ));

        final List<BlockHeader> previousHeaders = EXECUTION_WITNESS.getHeaders()
                .stream()
                .map(s -> BlockHeader.readFrom(RLP.input(Bytes.fromHexString(s)), new MainnetBlockHeaderFunctions()))
                .sorted(Comparator.comparing(BlockHeader::getNumber))
                .toList();

        BlockRunner runner = BlockRunner.create(previousHeaders,trieNodes, codes);

        runner.processBlock(BLOCK_TO_IMPORT);
    }

}