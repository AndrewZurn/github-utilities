import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required
import org.kohsuke.github.GHIssueState
import org.kohsuke.github.GHPullRequestQueryBuilder
import org.kohsuke.github.GitHubBuilder
import java.lang.RuntimeException
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

private const val OpenPRType = "OPEN"
private const val MergedPRType = "MERGED"
private val AllowedAnalysisTypes = listOf(OpenPRType, MergedPRType)
private const val TextOutputType = "TEXT"
private const val JsonOutputType = "JSON"
private val AllowedOutputTypes = listOf(TextOutputType, JsonOutputType)

private val JsonMapper = jacksonObjectMapper()

fun analyze(PRType: String, PRPullLimit: Int, repoName: String, outputType: String) {
    val github = GitHubBuilder.fromEnvironment().build()
    val repo = github.getRepository(repoName).queryPullRequests()

    if (PRType.equals(OpenPRType, ignoreCase = true)) {
        handleOpenPrAnalysis(repo, PRPullLimit, outputType)
    } else {
        handleMergedPrAnalysis(repo, PRPullLimit, outputType)
    }
}

private fun handleMergedPrAnalysis(
    repoName: GHPullRequestQueryBuilder,
    PRPullLimit: Int,
    outputType: String
) {
    fun printMergeStats(prefix: String, firstReviewDuration: Duration, mergeDuration: Duration) {
        println("${prefix} Time to First Review: ${firstReviewDuration.toDays()} days, ${firstReviewDuration.toHoursPart()} hours, ${firstReviewDuration.toMinutesPart()} minutes.")
        println("${prefix} Time to Merge: ${mergeDuration.toDays()} days, ${mergeDuration.toHoursPart()} hours, ${mergeDuration.toMinutesPart()} minutes.")
    }

    if (outputType.equals(TextOutputType, ignoreCase = true))
        println("\nClosed PR Statistics (limit ${PRPullLimit})\nWorking...")

    // get PRs that were successfully merged
    val mergedPRs =
        repoName.state(GHIssueState.CLOSED).list().take(PRPullLimit).filter { it.mergedAt != null }
    val timeToMergeDurations = mergedPRs.map {
        Duration.between(
            it.createdAt.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime(),
            it.mergedAt.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime()
        )
    }
    val timeToFirstReviewDurations = mergedPRs.map {
        val firstReviewTime = it.listReviews().first().createdAt.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime()
        Duration.between(
            it.createdAt.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime(),
            firstReviewTime
        )
    }

    val averageTimeToFirstReview =
        Duration.ofSeconds(timeToFirstReviewDurations.map { it.seconds }.sum() / mergedPRs.count())
    val averageTimeToMerge = Duration.ofSeconds(timeToMergeDurations.map { it.seconds }.sum() / mergedPRs.count())

    if (outputType.equals(JsonOutputType, ignoreCase = true)) {
        // yes this could be a function to make it a little less redundant... oh well
        val json = JsonMapper.writeValueAsString(
            mapOf(
                "average" to mapOf(
                    "firstReview" to averageTimeToFirstReview.seconds,
                    "merge" to averageTimeToMerge.seconds
                ),
                "max" to mapOf(
                    "firstReview" to timeToFirstReviewDurations.maxOrNull()!!.seconds,
                    "merge" to timeToMergeDurations.maxOrNull()!!.seconds
                ),
                "min" to mapOf(
                    "firstReview" to timeToFirstReviewDurations.minOrNull()!!.seconds,
                    "merge" to timeToMergeDurations.minOrNull()!!.seconds
                )
            )
        )
        println(json)
    } else {
        printMergeStats("Average", averageTimeToFirstReview, averageTimeToMerge)
        printMergeStats("Max", timeToFirstReviewDurations.maxOrNull()!!, timeToMergeDurations.maxOrNull()!!)
        printMergeStats("Min", timeToFirstReviewDurations.minOrNull()!!, timeToMergeDurations.minOrNull()!!)
    }
}

private fun handleOpenPrAnalysis(
    nerApiRepo: GHPullRequestQueryBuilder,
    PRPullLimit: Int,
    outputType: String
) {
    if (outputType.equals(TextOutputType, ignoreCase = true)) println("Open PRs\nWorking...")

    val openPRs = nerApiRepo.state(GHIssueState.OPEN).list().take(PRPullLimit)
    if (outputType.equals(JsonOutputType, ignoreCase = true)) {
        val json = JsonMapper.writeValueAsString(openPRs.map {
            mapOf(
                "number" to it.number,
                "title" to it.title,
                "createdAt" to it.createdAt.toInstant().atZone(ZoneOffset.UTC).toEpochSecond()
            )
        })
        println(json)
    } else {
        openPRs.forEach {
            val openDuration = Duration.between(
                it.createdAt.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime(),
                LocalDateTime.now(ZoneOffset.UTC)
            )
            println("PR: ${it.number} ${it.title} has been open for ${openDuration.toDays()} days, ${openDuration.toHoursPart()} hours.")
        }
    }
}

fun main(args: Array<String>) {
    val parser = ArgParser("Github PR Utility")
    val analyzePRType by parser.option(
        ArgType.String,
        fullName = "analyze",
        shortName = "a",
        description = "Analyze PR Type - ${AllowedAnalysisTypes}"
    ).required()
    val pullRequestPullLimit by parser.option(
        ArgType.Int,
        fullName = "pr-limit",
        shortName = "l",
        description = "Limit the amount of PRs to analyze."
    ).default(10)
    val repoName by parser.option(
        ArgType.String,
        fullName = "repo-name",
        shortName = "r",
        description = "The repository to analyze (user must have read permission)."
    ).required()
    val outputType by parser.option(
        ArgType.String,
        fullName = "output",
        shortName = "o",
        description = "How to output the analytics results. - ${AllowedOutputTypes}"
    ).default(TextOutputType)
    parser.parse(args)

    if (!AllowedAnalysisTypes.contains(analyzePRType.toUpperCase())) {
        throw RuntimeException("--analyze parameter must be of value ${AllowedAnalysisTypes}")
    }
    if (!AllowedOutputTypes.contains(outputType.toUpperCase())) {
        throw RuntimeException("--output parameter must be of value ${AllowedOutputTypes}")
    }
    analyze(analyzePRType, pullRequestPullLimit, repoName, outputType)
}

// not in Java 8, thus implementing directly
// https://docs.oracle.com/javase/9/docs/api/java/time/Duration.html#toHoursPart--
fun Duration.toHoursPart(): Long = this.toHours() % 24L

// not in Java 8, thus implementing directly
// https://docs.oracle.com/javase/9/docs/api/java/time/Duration.html#toMinutesPart--
fun Duration.toMinutesPart(): Long = this.toMinutes() % 60L
