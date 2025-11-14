import org.rancher.qa.airgap.AirgapSetupPipeline

def pipeline(def steps = this) {
    new AirgapSetupPipeline(steps)
}
