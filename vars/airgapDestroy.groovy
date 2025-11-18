import org.rancher.qa.airgap.AirgapDestroyPipeline

// Thin facade: expose destroy pipeline constructor while implementation lives in src/org/rancher/qa/airgap/AirgapDestroyPipeline.groovy
def pipeline(def steps = this) {
    new AirgapDestroyPipeline(steps)
}
