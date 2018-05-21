package cucumber.runtime;

import cucumber.api.TypeRegistryConfigurer;
import cucumber.api.StepDefinitionReporter;
import cucumber.api.event.TestRunFinished;
import cucumber.runner.EventBus;
import cucumber.runner.Runner;
import cucumber.runner.TimeService;
import cucumber.runtime.io.MultiLoader;
import cucumber.runtime.io.ResourceLoader;
import cucumber.runtime.model.CucumberFeature;
import gherkin.events.PickleEvent;
import gherkin.pickles.Compiler;
import gherkin.pickles.Pickle;
import io.cucumber.stepexpression.TypeRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static java.util.Collections.singletonList;

/**
 * This is the main entry point for running Cucumber features.
 */
public class Runtime {

    private final ExitStatus exitStatus = new ExitStatus();

    private final RuntimeOptions runtimeOptions;

    private final ResourceLoader resourceLoader;
    private final ClassLoader classLoader;
    private final Runner runner;
    private final Filters filters;
    private final EventBus bus;
    private final Compiler compiler = new Compiler();

    public Runtime(ResourceLoader resourceLoader, ClassFinder classFinder, ClassLoader classLoader, RuntimeOptions runtimeOptions) {
        this(resourceLoader, classLoader, loadBackends(resourceLoader, classFinder, runtimeOptions), runtimeOptions);
    }

    public Runtime(ResourceLoader resourceLoader, ClassLoader classLoader, Collection<? extends Backend> backends, RuntimeOptions runtimeOptions) {
        this(resourceLoader, classLoader, backends, runtimeOptions, TimeService.SYSTEM, null);
    }

    public Runtime(ResourceLoader resourceLoader, ClassLoader classLoader, Collection<? extends Backend> backends,
                   RuntimeOptions runtimeOptions, Glue optionalGlue) {
        this(resourceLoader, classLoader, backends, runtimeOptions, TimeService.SYSTEM, optionalGlue);
    }

    public Runtime(ResourceLoader resourceLoader, ClassLoader classLoader, Collection<? extends Backend> backends,
                   RuntimeOptions runtimeOptions, TimeService stopWatch, Glue optionalGlue) {
        if (backends.isEmpty()) {
            throw new CucumberException("No backends were found. Please make sure you have a backend module on your CLASSPATH.");
        }
        this.resourceLoader = resourceLoader;
        this.classLoader = classLoader;
        this.runtimeOptions = runtimeOptions;
        final Glue glue;
        glue = optionalGlue == null ? new RuntimeGlue() : optionalGlue;
        this.bus = new EventBus(stopWatch);
        this.runner = new Runner(glue, bus, backends, runtimeOptions);
        this.filters = new Filters(runtimeOptions, resourceLoader);
        exitStatus.setEventPublisher(bus);
        runtimeOptions.setEventBus(bus);
    }

    private static Collection<? extends Backend> loadBackends(ResourceLoader resourceLoader, ClassFinder classFinder, RuntimeOptions runtimeOptions) {
        Reflections reflections = new Reflections(classFinder);
        TypeRegistryConfigurer typeRegistryConfigurer = reflections.instantiateExactlyOneSubclass(TypeRegistryConfigurer.class, MultiLoader.packageName(runtimeOptions.getGlue()), new Class[0], new Object[0], new DefaultTypeRegistryConfiguration());
        TypeRegistry typeRegistry = new TypeRegistry(typeRegistryConfigurer.locale());
        typeRegistryConfigurer.configureTypeRegistry(typeRegistry);
        return reflections.instantiateSubclasses(Backend.class, singletonList("cucumber.runtime"), new Class[]{ResourceLoader.class, TypeRegistry.class}, new Object[]{resourceLoader, typeRegistry});
    }

    /**
     * This is the main entry point. Used from CLI, but not from JUnit.
     */
    public void run() {
        // Make sure all features parse before initialising any reporters/formatters
        List<CucumberFeature> features = runtimeOptions.cucumberFeatures(resourceLoader, bus);

        // TODO: This is duplicated in cucumber.api.android.CucumberInstrumentationCore - refactor or keep uptodate

        StepDefinitionReporter stepDefinitionReporter = runtimeOptions.stepDefinitionReporter(classLoader);

        reportStepDefinitions(stepDefinitionReporter);

        for (CucumberFeature cucumberFeature : features) {
            runFeature(cucumberFeature);
        }

        bus.send(new TestRunFinished(bus.getTime()));
    }

    public void reportStepDefinitions(StepDefinitionReporter stepDefinitionReporter) {
        runner.reportStepDefinitions(stepDefinitionReporter);
    }

    public void runFeature(CucumberFeature feature) {
        List<PickleEvent> pickleEvents = compileFeature(feature);
        for (PickleEvent pickleEvent : pickleEvents) {
            if (matchesFilters(pickleEvent)) {
                runner.runPickle(pickleEvent);
            }
        }
    }

    public List<PickleEvent> compileFeature(CucumberFeature feature) {
        List<PickleEvent> pickleEvents = new ArrayList<PickleEvent>();
        for (Pickle pickle : compiler.compile(feature.getGherkinFeature())) {
            pickleEvents.add(new PickleEvent(feature.getUri(), pickle));
        }
        return pickleEvents;
    }

    public boolean matchesFilters(PickleEvent pickleEvent) {
        return filters.matchesFilters(pickleEvent);
    }

    public byte exitStatus() {
        return exitStatus.exitStatus(runtimeOptions.isStrict());
    }

    public Glue getGlue() {
        return runner.getGlue();
    }

    public EventBus getEventBus() {
        return bus;
    }

    public Runner getRunner() {
        return runner;
    }
}
