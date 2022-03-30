import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required
import org.kohsuke.github.*
import java.time.*
import java.time.format.DateTimeFormatter

private const val OpenPRType = "OPEN"
private const val MergedPRType = "MERGED"
private const val CodeReviewReportType = "CODEREVIEW"
private val AllowedAnalysisTypes = listOf(OpenPRType, MergedPRType, CodeReviewReportType)
private const val TextOutputType = "TEXT"
private const val JsonOutputType = "JSON"
private val AllowedOutputTypes = listOf(TextOutputType, JsonOutputType)

private const val WORKSTARTTIME = 8  // Assuming start day at 8 am
private const val WORKENDTIME = 18  // End at 6 pm
private const val PRINTOUTPUTMESSAGES = false

private val JsonMapper = jacksonObjectMapper()

fun analyze(PRType: String, prPullLimit: Int, repoName: String, outputType: String, includeIndividualStats: Boolean) {
    val repo = getGithubRepo(repoName)
    if (PRType.equals(OpenPRType, ignoreCase = true)) {
        handleOpenPrAnalysis(repo, prPullLimit, outputType)
    } else {
        handleMergedPrAnalysis(repo, prPullLimit, outputType, includeIndividualStats)
    }
}

private fun getGithubRepo(repoName: String): GHRepository {
    val github = GitHubBuilder.fromEnvironment().build()
    return github.getRepository(repoName)
}

private fun getCodeReviewReportInfo(
    ticketsInReport: List<String>,
    PRPullLimit: Int,
    repoName: String,
    outputType: String
) {
    println("Code Review Report PR Printout")
    println("Gathering PRs, finding authors and changed files for list ${ticketsInReport.joinToString(", ")}...")
    val repo = getGithubRepo(repoName)
    repo.queryPullRequests().state(GHIssueState.CLOSED)
        .list()
        .take(PRPullLimit)
        .map { pr ->
            ticketsInReport
                .find { ticket -> pr.title.contains(ticket, ignoreCase = true) }
                ?.let { ticket -> ticket to pr } // keep the ticket num to the PR instance to allow for easier formatting later on
        }
        .filterNotNull()
        .forEach { (ticket, pr) ->
            val filesChanged = pr.listFiles().map { file ->
                if (file.previousFilename != null) "${file.previousFilename} -> ${file.filename}"
                else file.filename
            }

            println("Ticket: ${ticket}")
            println("Reviewers: ${pr.listReviews().joinToString(", ") { it.user.login }}")
            println("Files Changed:")
            println(filesChanged.joinToString("\r\n"))
            println("====================")
        }
}

private fun handleMergedPrAnalysis(
    repo: GHRepository,
    PRPullLimit: Int,
    outputType: String,
    includeIndividualStats: Boolean
) {
    if (outputType.equals(TextOutputType, ignoreCase = true))
        println("\nClosed PR Statistics (limit ${PRPullLimit})\nWorking...")

    // get PRs that were successfully merged
    println("Gathering merged PRs...")
    val mergedPRs = repo.queryPullRequests().state(GHIssueState.CLOSED)
        .list()
        .take(PRPullLimit)
        .filter { it.mergedAt != null }
        .filter { it.labels.map { label -> label.name }.contains("exclude-from-analysis").not() }

    val timeToMergeDurations = mergedPRs.map {
        calcDurationWithoutWeekends(
            it.createdAt.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
            it.mergedAt.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
        )
    }

    println("Gathering time to first review durations...")
    val timeToFirstReviewDurations = mergedPRs.map {
        val reviews = it.listReviews()
        if (reviews.any()) { // if a PR didn't have a review but was merged
            val firstReview = reviews.first { it.state != GHPullRequestReviewState.PENDING }
            val firstReviewTime = firstReview.createdAt.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
            calcDurationWithoutWeekends(
               it.createdAt.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
                firstReviewTime
            )
        } else {
            null
        }
    }.filterNotNull()

    println("Analyzing average time to first review...")
    val averageTimeToFirstReview =
        Duration.ofSeconds(timeToFirstReviewDurations.map { it.seconds }.sum() / mergedPRs.count())
    println("Analyzing average time to merge...")
    val averageTimeToMerge = Duration.ofSeconds(timeToMergeDurations.map { it.seconds }.sum() / mergedPRs.count())

    val individualContributorStats =
        if (includeIndividualStats) getIndividualStatistics(repo.listContributors().map { it.login }, mergedPRs)
        else emptyList()

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
                ),
                "individualStats" to individualContributorStats.map {
                    mapOf(
                        "name" to it.author,
                        "submittedReviews" to it.submittedReviews,
                        "wasRequestedReviews" to it.wasRequestedReviews
                    )
                }
            )
        )
        println(json)
    } else {
        printMergeStats("Average", averageTimeToFirstReview, averageTimeToMerge)
        printMergeStats("Max", timeToFirstReviewDurations.maxOrNull()!!, timeToMergeDurations.maxOrNull()!!)
        printMergeStats("Min", timeToFirstReviewDurations.minOrNull()!!, timeToMergeDurations.minOrNull()!!)
        individualContributorStats.filter { it.wasRequestedReviews > 0 || it.submittedReviews > 0 }.map {
            println(
                "Name: ${it.author} " +
                        "- Submitted Reviews: ${it.submittedReviews} " +
                        "- Was Requested Review: ${it.wasRequestedReviews}"
            )
        }
    }
}
/*
 Given a Local DateTime, normalizes the Date into the work time between WORKSTARTTIME and WORKENDTIME, excluding weekends.

 If the date falls outside the workday window, the normalized date will be the start of the next working day.
 */
private fun getWorkHourDate(someDate:LocalDateTime) : LocalDateTime
{
    var normalizedLocalDateTime : LocalDateTime = someDate

    if ( someDate.hour < WORKSTARTTIME) {
        normalizedLocalDateTime = LocalDateTime.of(LocalDate.of(someDate.year, someDate.month, someDate.dayOfMonth), LocalTime.of(WORKSTARTTIME,0))
    }
    if ( someDate.hour >= WORKENDTIME) {
        val tomorrowDate = someDate.plusDays(1);
        normalizedLocalDateTime = LocalDateTime.of(LocalDate.of(tomorrowDate.year, tomorrowDate.month, tomorrowDate.dayOfMonth), LocalTime.of(WORKSTARTTIME,0))
    }

    // If start on a weekend, move to first moment of Monday.
    if (normalizedLocalDateTime.dayOfWeek.equals(DayOfWeek.SATURDAY))
        normalizedLocalDateTime = normalizedLocalDateTime.plusDays(2).toLocalDate().atStartOfDay()
    if (normalizedLocalDateTime.dayOfWeek.equals(DayOfWeek.SUNDAY))
        normalizedLocalDateTime = normalizedLocalDateTime.plusDays(1).toLocalDate().atStartOfDay()

    return normalizedLocalDateTime
}

private fun calcDurationWithoutWeekends(startLocalDateTime: LocalDateTime, endLocalDateTime: LocalDateTime) : Duration {

    val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHH")

    var start: LocalDateTime = getWorkHourDate(startLocalDateTime)

    if (PRINTOUTPUTMESSAGES) { println("Start is "+startLocalDateTime) }
    if (PRINTOUTPUTMESSAGES) { println("Normalized Start is "+start) }

    var stop: LocalDateTime = getWorkHourDate(endLocalDateTime)

    if (PRINTOUTPUTMESSAGES) { println("Stop is "+endLocalDateTime)}
    if (PRINTOUTPUTMESSAGES) { println("Normalized Stop is "+stop) }

    if ((start.isEqual(stop))||(start.isAfter(stop))) { // error states, no calc to do
        0
    }

    if (start.toLocalDate().equals(stop.toLocalDate())) {
        if (PRINTOUTPUTMESSAGES) { println("hourly duration is "+Duration.between(start,stop))}
        return Duration.between(start,stop)
    }

    val firstMomentOfDayAfterStart = start.toLocalDate().plusDays(1).atStartOfDay()
    val firstDayDuration = Duration.between(start, LocalDateTime.of(LocalDate.of(start.year, start.month, start.dayOfMonth), LocalTime.of(WORKENDTIME,0)))
    if (PRINTOUTPUTMESSAGES) { println("firstDayDuration is "+firstDayDuration)}
    val lastDayDuration = Duration.between(LocalDateTime.of(LocalDate.of(stop.year, stop.month, stop.dayOfMonth), LocalTime.of(WORKSTARTTIME,0)), stop)
    if (PRINTOUTPUTMESSAGES) { println("lastDayDuration is "+lastDayDuration)}

    var countWeekdays : Long = 0;
    var firstMomentOfSomeDay = firstMomentOfDayAfterStart;
    while( firstMomentOfSomeDay.toLocalDate().isBefore( stop.toLocalDate() ) ) {
        var dayOfWeek = firstMomentOfSomeDay.getDayOfWeek();
        if( dayOfWeek.equals( DayOfWeek.SATURDAY ) || dayOfWeek.equals( DayOfWeek.SUNDAY ) ) {
            // ignore this day.
        } else {
            countWeekdays ++  // Tally another weekday.
        }
        // Set up the next loop.
        firstMomentOfSomeDay = firstMomentOfSomeDay.plusDays( 1 )
    }
    if (PRINTOUTPUTMESSAGES) { println("count of middle days is "+countWeekdays)}
    if (PRINTOUTPUTMESSAGES) { println("total duration is "+firstDayDuration.toString() + Duration.ofDays(countWeekdays) + lastDayDuration.toString())}

    return firstDayDuration + Duration.ofHours(countWeekdays*8) + lastDayDuration
}


private fun printMergeStats(prefix: String, firstReviewDuration: Duration, mergeDuration: Duration) {
    println("$prefix Time to First Review: ${firstReviewDuration.toDays()} days, ${firstReviewDuration.toHoursPart()} hours, ${firstReviewDuration.toMinutesPart()} minutes.")
    println("$prefix Time to Merge: ${mergeDuration.toDays()} days, ${mergeDuration.toHoursPart()} hours, ${mergeDuration.toMinutesPart()} minutes.")
}

private fun getIndividualStatistics(contributorNames: List<String>, prs: List<GHPullRequest>): List<ContributorStats> {
    println("Gathering individual contributor statistics...")
    val prsWithReviews = prs.map { it to it.listReviews() }

    // Let's Map/Reduce (although a bit clunky with the Kotlin mechanics...)
    // For each PR get the flattened list of contributors, group by each contributor name, then count the
    // number of times their name occurred as having submitted a review to a PR.
    val contributorsToPrs = prsWithReviews
        .flatMap { pr ->
            pr.second
                .flatMap { review -> contributorNames.mapNotNull { if (it.contains(review.user.login)) it else null } }
                .distinct() // ensure that if someone submitted multiple reviews in a PR they're numbers arn't skewed
        }
        .groupBy { it }
        .mapValues { it.key to it.value.count() }

    // For each PR, get the flattened list of requested reviewers, group by each contributor name,
    // then count the number of times their name occurred as having been asked for a review.
    val wasRequestedToReviewPr = prsWithReviews
        .flatMap { pr -> pr.first.requestedReviewers.map { it.login } }
        .groupBy { it }
        .mapValues { it.key to it.value.count() }

    return contributorNames.map { contributorName ->
        println("Generating statistics for: $contributorName")
        ContributorStats(
            author = contributorName,
            submittedReviews = contributorsToPrs[contributorName]?.second ?: 0,
            wasRequestedReviews = wasRequestedToReviewPr[contributorName]?.second ?: 0
        )
    }
}

data class ContributorStats(
    val author: String,
    val submittedReviews: Int,
    val wasRequestedReviews: Int
)

private fun handleOpenPrAnalysis(repo: GHRepository, PRPullLimit: Int, outputType: String) {
    if (outputType.equals(TextOutputType, ignoreCase = true)) println("Open PRs\nWorking...")

    val openPRs = repo.queryPullRequests().state(GHIssueState.OPEN).list().take(PRPullLimit)
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
            val openDuration = calcDurationWithoutWeekends(
                    it.createdAt.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
                    LocalDateTime.now(ZoneId.systemDefault())
                )
            println("PR: ${it.number} ${it.title} has been open for ${openDuration.toDays()} days, ${openDuration.toHoursPart()} hours.")
        }
    }
}

fun main(args: Array<String>) {
    val parser = ArgParser("Github PR Utility")
    val analysisType by parser.option(
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
    val repositoryName by parser.option(
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
    val includeIndividualStats by parser.option(
        ArgType.Boolean,
        fullName = "individual-stats",
        shortName = "i",
        description = "Should include statistics on each individual contributor."
    ).default(false)
    val ticketsInReport by parser.option(
        ArgType.String,
        fullName = "tickets",
        shortName = "t",
        description = "The a comma-separated list of tickets to be included in the code review report printout."
    )
    parser.parse(args)

    if (!AllowedAnalysisTypes.contains(analysisType.uppercase())) {
        throw RuntimeException("--analyze parameter must be of value ${AllowedAnalysisTypes}")
    }
    if (!AllowedOutputTypes.contains(outputType.uppercase())) {
        throw RuntimeException("--output parameter must be of value ${AllowedOutputTypes}")
    }

    if (analysisType.equals(CodeReviewReportType, ignoreCase = true)) {
        if (ticketsInReport == null)
            throw RuntimeException("--tickets can not be empty when doing a Code Review Report printout.")
        getCodeReviewReportInfo(ticketsInReport!!.split(","), pullRequestPullLimit, repositoryName, outputType)
    } else {
        analyze(analysisType, pullRequestPullLimit, repositoryName, outputType, includeIndividualStats)
    }
}
