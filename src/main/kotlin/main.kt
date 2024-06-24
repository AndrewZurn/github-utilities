import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.multiple
import kotlinx.cli.required
import org.kohsuke.github.*
import java.time.*
import java.time.format.DateTimeFormatter

private const val OPEN_PR_TYPE = "OPEN"
private const val MERGED_PR_TYPE = "MERGED"
private const val CODE_REVIEW_REPORT_TYPE = "CODEREVIEW"
private val ALLOWED_ANALYSIS_TYPES = listOf(OPEN_PR_TYPE, MERGED_PR_TYPE, CODE_REVIEW_REPORT_TYPE)
private const val TEXT_OUTPUT_TYPE = "TEXT"
private const val JSON_OUTPUT_TYPE = "JSON"
private val ALLOWED_OUTPUT_TYPES = listOf(TEXT_OUTPUT_TYPE, JSON_OUTPUT_TYPE)

private const val WORK_START_TIME = 8  // Assuming start day at 8 am
private const val WORK_END_TIME = 18  // End at 6 pm

private val JsonMapper = jacksonObjectMapper()

fun analyze(
    prType: String,
    reposAndPullRequestsLimits: List<Pair<String, Int>>,
    outputType: String,
    includeIndividualStats: Boolean,
    inclusionLabels: List<String> = emptyList()) {
    if (prType.equals(OPEN_PR_TYPE, ignoreCase = true)) {
        handleOpenPrAnalysis(reposAndPullRequestsLimits, outputType)
    } else {
        handleMergedPrAnalysis(reposAndPullRequestsLimits, outputType, includeIndividualStats, inclusionLabels)
    }
}



private fun getGithubRepo(repoName: String): GHRepository {
    val github = GitHubBuilder.fromEnvironment().build()
    return github.getRepository(repoName)
}

// create a function that will calculate the duration between two dates, excluding weeknights after 5pm and weekends
private fun calcDurationWithoutWeekends2(start: LocalDateTime, end: LocalDateTime): Duration {
    var duration = Duration.between(start, end)
    var current = start
    while (current.isBefore(end)) {
        if (current.dayOfWeek == DayOfWeek.SATURDAY || current.dayOfWeek == DayOfWeek.SUNDAY) {
            duration = duration.minusHours(24)
        } else if (current.hour >= WORK_END_TIME) {
            duration = duration.minusHours((current.hour - WORK_END_TIME).toLong())
        } else if (current.hour < WORK_START_TIME) {
            duration = duration.minusHours((WORK_START_TIME - current.hour).toLong())
        }
        current = current.plusHours(1)
    }
    return duration
}

private fun getCodeReviewReportInfo(
    ticketsInReport: List<String>,
    reposAndPullRequestsLimits: List<Pair<String, Int>>,
    outputType: String
) {
    reposAndPullRequestsLimits.map { (repoName, prPullLimit) ->
        println("Code Review Report PR Printout for repo: $repoName - Pull Request Pull Limit: $prPullLimit")
        println("Gathering PRs, finding authors and changed files for list: ${ticketsInReport.joinToString(", ")}...")
        val numberRegex = "(\\d+)".toRegex()
        val repo = getGithubRepo(repoName)
        repo.queryPullRequests().state(GHIssueState.CLOSED)
            .list()
            .take(prPullLimit)
            .filter { pr -> pr.isMerged }
            .map { pr ->
                val prTitle = pr.title
                ticketsInReport
                    .find { ticket ->
                        val ticketNumber = numberRegex.find(ticket)?.value ?: ""
                        prTitle.contains(ticket, ignoreCase = true) || prTitle.contains(ticketNumber, ignoreCase = true)
                    }
                    ?.let { ticket -> ticket to pr } // keep the ticket num to the PR instance to allow for easier formatting later on
            }
            .filterNotNull()
            .forEach { (ticket, pr) ->
                val filesChanged = pr.listFiles().map { file ->
                    if (file.previousFilename != null) "${file.previousFilename} -> ${file.filename}"
                    else file.filename
                }

                println("Ticket: $ticket")
                println("Pull Request URL: ${pr.url}")
                println("Reviewers: ${pr.listReviews().joinToString(", ") { it.user.login }}")
                println("Files Changed:")
                println(filesChanged.joinToString("\r\n"))
                println("====================")
            }
    }
}

private fun handleMergedPrAnalysis(
    reposAndPullRequestsLimits: List<Pair<String, Int>>,
    outputType: String,
    includeIndividualStats: Boolean,
    inclusionLabels: List<String> = emptyList()
) {
    reposAndPullRequestsLimits.map { (repoName, prPullLimit) ->
        if (outputType.equals(TEXT_OUTPUT_TYPE, ignoreCase = true))
            println("\nClosed PR Statistics for repo: $repoName - Pull Request Pull Limit: $prPullLimit\nWorking...")

        val repo = getGithubRepo(repoName)

        // get PRs that were successfully merged
        print("Gathering merged PRs... ")
        val closedPrs = repo.queryPullRequests().state(GHIssueState.CLOSED)
            .list()
            .take(prPullLimit)
        val mergedPRs = closedPrs
            .filter { it.isMerged }
            // .filter { it.labels.map { label -> label.name }.contains("exclude-from-analysis").not() }
            // .filter { it.labels.map { label -> label.name }.any { labelName -> inclusionLabels.contains(labelName) } }

        println("Number of PRs to analyze: ${mergedPRs.count()}")
        println("PR Numbers: ${mergedPRs.map { it.number }.joinToString(", ")}")

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

        if (outputType.equals(JSON_OUTPUT_TYPE, ignoreCase = true)) {
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
            println("Results for repo: $repoName")
            printMergeStats("Average", averageTimeToFirstReview, averageTimeToMerge)
            printMergeStats("Max", timeToFirstReviewDurations.maxOrNull()!!, timeToMergeDurations.maxOrNull()!!)
            individualContributorStats.filter { it.wasRequestedReviews > 0 || it.submittedReviews > 0 }.map {
                println(
                    "Name: ${it.author} " +
                        "- Submitted Reviews: ${it.submittedReviews} " +
                        "- Was Requested Review: ${it.wasRequestedReviews}"
                )
            }
        }
    }
}

/*
 Given a Local DateTime, normalizes the Date into the work time between WORKSTARTTIME and WORKENDTIME, excluding weekends.

 If the date falls outside the workday window, the normalized date will be the start of the next working day.
 */
private fun getWorkHourDate(someDate: LocalDateTime): LocalDateTime {
    var normalizedLocalDateTime: LocalDateTime = someDate

    if (someDate.hour < WORK_START_TIME) {
        normalizedLocalDateTime = LocalDateTime.of(
            LocalDate.of(someDate.year, someDate.month, someDate.dayOfMonth),
            LocalTime.of(WORK_START_TIME, 0)
        )
    }
    if (someDate.hour >= WORK_END_TIME) {
        val tomorrowDate = someDate.plusDays(1)
        normalizedLocalDateTime = LocalDateTime.of(
            LocalDate.of(tomorrowDate.year, tomorrowDate.month, tomorrowDate.dayOfMonth),
            LocalTime.of(WORK_START_TIME, 0)
        )
    }

    // If start on a weekend, move to first moment of Monday.
    if (normalizedLocalDateTime.dayOfWeek.equals(DayOfWeek.SATURDAY))
        normalizedLocalDateTime = normalizedLocalDateTime.plusDays(2).toLocalDate().atStartOfDay()
    if (normalizedLocalDateTime.dayOfWeek.equals(DayOfWeek.SUNDAY))
        normalizedLocalDateTime = normalizedLocalDateTime.plusDays(1).toLocalDate().atStartOfDay()

    return normalizedLocalDateTime
}

private fun calcDurationWithoutWeekends(startLocalDateTime: LocalDateTime, endLocalDateTime: LocalDateTime): Duration {
    val start: LocalDateTime = getWorkHourDate(startLocalDateTime)
    val stop: LocalDateTime = getWorkHourDate(endLocalDateTime)

    if (start.isAfter(stop)) { // error states, no calc to do
        throw IllegalArgumentException("Start ${start} cannot be after end: ${stop}")
    }

    if (start.toLocalDate().equals(stop.toLocalDate())) {
        return Duration.between(start, stop)
    }

    val firstMomentOfDayAfterStart = start.toLocalDate().plusDays(1).atStartOfDay()
    val firstDayDuration = Duration.between(
        start,
        LocalDateTime.of(LocalDate.of(start.year, start.month, start.dayOfMonth), LocalTime.of(WORK_END_TIME, 0))
    )

    val lastDayDuration = Duration.between(
        LocalDateTime.of(
            LocalDate.of(stop.year, stop.month, stop.dayOfMonth),
            LocalTime.of(WORK_START_TIME, 0)
        ), stop
    )

    var countWeekdays: Long = 0
    var firstMomentOfSomeDay = firstMomentOfDayAfterStart
    while (firstMomentOfSomeDay.toLocalDate().isBefore(stop.toLocalDate())) {
        val dayOfWeek = firstMomentOfSomeDay.dayOfWeek

        if (!dayOfWeek.equals(DayOfWeek.SATURDAY) && !dayOfWeek.equals(DayOfWeek.SUNDAY)) countWeekdays++
        // Set up the next loop.
        firstMomentOfSomeDay = firstMomentOfSomeDay.plusDays(1)
    }

    return firstDayDuration + Duration.ofHours(countWeekdays * 8) + lastDayDuration
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

private fun handleOpenPrAnalysis(reposAndPullRequestsLimits: List<Pair<String, Int>>, outputType: String) {
    reposAndPullRequestsLimits.map { (repoName, prPullLimit) ->
        if (outputType.equals(TEXT_OUTPUT_TYPE, ignoreCase = true))
            println("Open PRs for repo: $repoName - Pull Request Pull Limit: $prPullLimit\nWorking...")

        val repo = getGithubRepo(repoName)
        val openPRs = repo.queryPullRequests().state(GHIssueState.OPEN).list().take(prPullLimit)
        if (outputType.equals(JSON_OUTPUT_TYPE, ignoreCase = true)) {
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
}

fun main(args: Array<String>) {
    val parser = ArgParser("Github PR Utility")
    val analysisType by parser.option(
        ArgType.String,
        fullName = "analyze",
        shortName = "a",
        description = "Analyze PR Type - ${ALLOWED_ANALYSIS_TYPES}"
    ).required()
    val pullRequestPullLimit by parser.option(
        ArgType.Int,
        fullName = "pr-limit",
        shortName = "l",
        description = "Limit the amount of PRs to analyze."
    ).default(10).multiple()
    val repositoryNames by parser.option(
        ArgType.String,
        fullName = "repo-name",
        shortName = "r",
        description = "The repository to analyze (user must have read permission)."
    ).required().multiple()
    val outputType by parser.option(
        ArgType.String,
        fullName = "output",
        shortName = "o",
        description = "How to output the analytics results. - ${ALLOWED_OUTPUT_TYPES}"
    ).default(TEXT_OUTPUT_TYPE)
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
    val inclusionLabels by parser.option(
        ArgType.String,
        fullName = "include-labels",
        shortName = "il",
        description = "The a comma-separated list of labels to be included in the code review report printout."
    )
    parser.parse(args)

    if (!ALLOWED_ANALYSIS_TYPES.contains(analysisType.uppercase())) {
        throw RuntimeException("--analyze parameter must be of value ${ALLOWED_ANALYSIS_TYPES}")
    }
    if (!ALLOWED_OUTPUT_TYPES.contains(outputType.uppercase())) {
        throw RuntimeException("--output parameter must be of value ${ALLOWED_OUTPUT_TYPES}")
    }

    val reposAndPullRequestsLimits = when (pullRequestPullLimit.size) {
        1 -> repositoryNames.map { it to pullRequestPullLimit.first() }
        repositoryNames.size -> repositoryNames.zip(pullRequestPullLimit)
        else ->
            throw RuntimeException("'pr-limit' option must have a single provided argument or must the length of the 'repo-name' option.")
    }

    if (analysisType.equals(CODE_REVIEW_REPORT_TYPE, ignoreCase = true)) {
        if (ticketsInReport == null)
            throw RuntimeException("--tickets can not be empty when doing a Code Review Report printout.")
        getCodeReviewReportInfo(ticketsInReport!!.split(","), reposAndPullRequestsLimits, outputType)
    } else {
        analyze(
            prType = analysisType,
            reposAndPullRequestsLimits = reposAndPullRequestsLimits,
            outputType = outputType,
            includeIndividualStats = includeIndividualStats,
            inclusionLabels = inclusionLabels?.split(",") ?: emptyList()
        )
    }
}
