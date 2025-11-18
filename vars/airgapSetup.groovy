import org.rancher.qa.airgap.AirgapSetupPipeline

// Thin facade: expose setup pipeline constructor while implementation lives in src/org/rancher/qa/airgap/AirgapSetupPipeline.groovy
def pipeline(def steps = this) {
    new AirgapSetupPipeline(steps)
}
